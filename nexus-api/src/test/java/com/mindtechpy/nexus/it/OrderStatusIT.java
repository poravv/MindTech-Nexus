package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mindtechpy.nexus.application.usecase.StartOperation;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.operation.OperationStep;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code [NEXUS-ORDER]} ORDER_STATUS: the POS reports a collected or voided channel sale and GiftHub follows. */
class OrderStatusIT extends AbstractConnectorIT {

    private UUID integration;
    private String orderId;
    private String posClient;

    @BeforeEach
    void setUpOrder() {
        integration = orderIntegration();
        orderId = UUID.randomUUID().toString();
        Operation created = startOperation.execute(new StartOperation.Command(new TenantId(tenant), integration,
                OperationKind.CREATE_ORDER, orderId, Map.of("id", orderId), null)).operation();
        jdbc.sql("UPDATE operations SET status = 'SUCCEEDED', result = CAST(:result AS jsonb) WHERE id = :id")
                .param("result", "{\"saleId\":\"sale-1\",\"saleNumber\":\"SALE-00000042\"}").param("id", created.id()).update();
        posClient = clientToken(tenant, "orders:write");
    }

    @Test
    void should_mark_paid_and_walk_every_valid_transition_to_delivered_when_pos_reports_completed() {
        List<String> chain = List.of("CONFIRMED", "PREPARING", "READY", "OUT_FOR_DELIVERY", "DELIVERED");
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).inScenario("gh").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(giftHubOrder(orderId, "CONFIRMED", "PENDING", "DELIVERY"))));
        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/paid"))).inScenario("gh").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(giftHubOrder(orderId, "CONFIRMED", "PAID", "DELIVERY"))).willSetStateTo("CONFIRMED"));
        for (int i = 0; i < chain.size(); i++) {
            String state = chain.get(i);
            stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).inScenario("gh").whenScenarioStateIs(state)
                    .willReturn(okJson(giftHubOrder(orderId, state, "PAID", "DELIVERY"))));
            if (i + 1 < chain.size()) {
                String next = chain.get(i + 1);
                stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/status"))).inScenario("gh").whenScenarioStateIs(state)
                        .withRequestBody(matchingJsonPath("$.status", equalTo(next)))
                        .willReturn(okJson(giftHubOrder(orderId, next, "PAID", "DELIVERY"))).willSetStateTo(next));
            }
        }

        JsonNode started = report("COMPLETED", 202);
        drain();

        UUID opId = UUID.fromString(started.get("operationId").asString());
        Operation done = reload(opId);
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(operations.findSteps(new TenantId(tenant), opId)).extracting(OperationStep::externalRef)
                .containsExactly("PAID", "PREPARING", "READY", "OUT_FOR_DELIVERY", "DELIVERED");
        JsonNode paid = jsonOf(giftHubRequests("POST", "/orders/" + orderId + "/paid").getFirst());
        assertThat(paid.get("method").asString()).isEqualTo("CASH");
        assertThat(paid.get("reference").asString()).contains("SALE-00000042");
        assertThat(giftHubRequests("POST", "/orders/" + orderId + "/status")).extracting(r -> r.getHeader("Idempotency-Key"))
                .containsExactly("nexus:" + opId + ":PREPARING", "nexus:" + opId + ":READY",
                        "nexus:" + opId + ":OUT_FOR_DELIVERY", "nexus:" + opId + ":DELIVERED");

        JsonNode repeated = report("COMPLETED", 200);
        assertThat(repeated.get("operationId").asString()).isEqualTo(opId.toString());
    }

    @Test
    void should_cancel_in_gifthub_when_pos_voids_the_sale() {
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "CONFIRMED", "PENDING", "PICKUP"))));
        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/status")))
                .willReturn(okJson(giftHubOrder(orderId, "CANCELLED", "PENDING", "PICKUP"))));

        UUID opId = UUID.fromString(report("CANCELLED", 202).get("operationId").asString());
        drain();

        assertThat(reload(opId).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(jsonOf(giftHubRequests("POST", "/orders/" + orderId + "/status").getFirst()).get("status").asString())
                .isEqualTo("CANCELLED");
    }

    @Test
    void should_not_cancel_and_ask_for_manual_reconciliation_when_pos_voids_an_order_paid_in_gifthub() {
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "CONFIRMED", "PAID", "PICKUP"))));

        UUID opId = UUID.fromString(report("CANCELLED", 202).get("operationId").asString());
        drain();

        Operation dead = reload(opId);
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("manual-reconciliation");
        assertThat(giftHubRequests("POST", "/orders/" + orderId + "/status")).isEmpty();
    }

    @Test
    void should_answer_404_when_another_tenant_reports_this_order() {
        UUID otherTenant = createTenant(admin);
        String otherClient = clientToken(otherTenant, "orders:write");

        perform(MockMvcRequestBuilders.post("/api/v1/orders/GIFTHUB/{ref}/status", orderId).header("Authorization", bearer(otherClient))
                .contentType(MediaType.APPLICATION_JSON).content(statusBody("COMPLETED")), 404);
        perform(MockMvcRequestBuilders.post("/api/v1/orders/GIFTHUB/{ref}/status", orderId).header("Authorization", bearer(clientToken(tenant, "orders:read")))
                .contentType(MediaType.APPLICATION_JSON).content(statusBody("COMPLETED")), 403);
    }

    private JsonNode report(String status, int expected) {
        return body(perform(MockMvcRequestBuilders.post("/api/v1/orders/GIFTHUB/{ref}/status", orderId).header("Authorization", bearer(posClient))
                .contentType(MediaType.APPLICATION_JSON).content(statusBody(status)), expected));
    }

    private String statusBody(String status) {
        return toJson(Map.of("status", status, "saleNumber", "SALE-00000042", "paidAmount", 91000, "paymentMethod", "CASH",
                "occurredAt", "2026-10-10T12:00:00Z"));
    }
}
