package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.operation.OperationStep;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code [NEXUS-ORDER]} CREATE_ORDER by branch: GiftHub webhook → POS sale → GiftHub CONFIRMED, and its compensations. */
class OrderSagaIT extends AbstractConnectorIT {

    private static final String SALE = """
            {"saleId":"sale-1","saleNumber":"SALE-00000042","total":91000,"status":"PENDING"}""";

    private String orderId;

    @BeforeEach
    void setUpOrder() {
        orderIntegration();
        orderId = UUID.randomUUID().toString();
        stubPos(post(urlPathEqualTo(pos("/api/integration/customers/resolve")))
                .willReturn(jsonResponse("{\"customerId\":\"c-maria\",\"name\":\"María López\",\"created\":true}", 201)));
        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/status")))
                .willReturn(okJson(giftHubOrder(orderId, "CONFIRMED", "PENDING", "DELIVERY"))));
    }

    @Test
    void should_create_pos_sale_and_confirm_in_gifthub_when_order_is_created() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(SALE, 201)));

        Operation op = receiveOrder(maria());
        drain();

        Operation done = reload(op.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("saleNumber", "SALE-00000042").containsEntry("customerId", "c-maria");
        assertThat(operations.findSteps(new TenantId(tenant), op.id())).extracting(OperationStep::step)
                .containsExactly("RESOLVE_CUSTOMER", "CREATE_ORDER", "NOTIFY_GIFTHUB");

        var create = posRequests("POST", "/api/integration/orders").getFirst();
        assertThat(create.getHeader("Idempotency-Key")).isEqualTo("GIFTHUB:" + orderId);
        assertThat(create.getHeader("X-Api-Key")).isEqualTo("pv_live_test");
        JsonNode sale = jsonOf(create);
        assertThat(sale.propertyNames()).containsExactlyInAnyOrder("channel", "externalRef", "customerId", "items",
                "delivery", "paymentMethod", "notes");
        assertThat(sale.get("customerId").asString()).isEqualTo("c-maria");
        assertThat(sale.get("items").get(0).get("code").asString()).isEqualTo("SKU-1001");
        assertThat(sale.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(sale.get("notes").asString()).contains("BH-7Q2K", "Sin cebolla", "Extra cheddar");
        assertThat(jsonOf(posRequests("POST", "/api/integration/customers/resolve").getFirst()).get("phoneE164").asString())
                .isEqualTo("+595981123456");

        var confirm = giftHubRequests("POST", "/orders/" + orderId + "/status").getFirst();
        assertThat(confirm.getHeader("Authorization")).isEqualTo("Bearer gh_live_test");
        assertThat(confirm.getHeader("Idempotency-Key")).isEqualTo("nexus:" + op.id() + ":CONFIRMED");
        JsonNode status = jsonOf(confirm);
        assertThat(status.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(status.get("external_ref").asString()).isEqualTo("sale-1");
        assertThat(status.get("external_number").asString()).isEqualTo("SALE-00000042");
        assertThat(mapping("ORDER", orderId)).isEqualTo("sale-1");
        assertThat(mapping("CUSTOMER", "+595981123456")).isEqualTo("c-maria");
    }

    @Test
    void should_use_default_customer_of_connection_when_event_has_no_customer() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(SALE, 201)));

        Operation op = receiveOrder(null);
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", "/api/integration/customers/resolve")).isEmpty();
        assertThat(jsonOf(posRequests("POST", "/api/integration/orders").getFirst()).get("customerId").asString())
                .isEqualTo("c-default");
    }

    @Test
    void should_create_the_sale_without_payment_method_when_the_order_has_none() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(SALE, 201)));
        String delivery = UUID.randomUUID().toString();
        String body = orderCreated(delivery, orderId, maria()).replace("\"payment_method\":\"TRANSFER\",", "");
        JsonNode received = sendSigned(delivery, body, 200);

        drain();

        assertThat(reload(UUID.fromString(received.get("operationId").asString())).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(jsonOf(posRequests("POST", "/api/integration/orders").getFirst()).has("paymentMethod")).isFalse();
    }

    @Test
    void should_cancel_in_gifthub_with_reason_and_fail_when_pos_rejects_for_stock() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(
                "{\"code\":\"insufficient-stock\",\"message\":\"Stock insuficiente para SKU-1001\"}", 422)));
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "PENDING", "PENDING", "DELIVERY"))));

        Operation op = receiveOrder(maria());
        drain();

        Operation failed = reload(op.id());
        assertThat(failed.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(failed.lastError().code()).isEqualTo("insufficient-stock");
        var cancel = giftHubRequests("POST", "/orders/" + orderId + "/status");
        assertThat(cancel).hasSize(1);
        assertThat(jsonOf(cancel.getFirst()).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(jsonOf(cancel.getFirst()).get("note").asString()).contains("Stock insuficiente");
        assertThat(cancel.getFirst().getHeader("Idempotency-Key")).isEqualTo("nexus:" + op.id() + ":CANCELLED");
    }

    @Test
    void should_not_cancel_and_ask_for_manual_reconciliation_when_rejected_order_is_already_paid_in_gifthub() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(
                "{\"code\":\"insufficient-stock\",\"message\":\"Stock insuficiente\"}", 422)));
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "PENDING", "PAID", "DELIVERY"))));

        Operation op = receiveOrder(maria());
        drain();

        Operation dead = reload(op.id());
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("manual-reconciliation");
        assertThat(dead.lastError().detail()).contains("pagado en GiftHub");
        assertThat(giftHubRequests("POST", "/orders/" + orderId + "/status")).isEmpty();
    }

    @Test
    void should_adopt_existing_sale_without_resending_when_verification_after_timeout_finds_it() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(SALE, 201).withFixedDelay(2500)));
        stubPos(get(urlPathEqualTo(pos("/api/integration/orders/GIFTHUB/" + orderId))).willReturn(okJson(SALE)));

        Operation op = receiveOrder(maria());
        drain();
        Operation uncertain = reload(op.id());
        assertThat(uncertain.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(uncertain.lastError().kind().name()).isEqualTo("UNCERTAIN");
        makeDue(op.id());
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", "/api/integration/orders")).hasSize(1);
        assertThat(posRequests("GET", "/api/integration/orders/GIFTHUB/" + orderId)).hasSize(1);
    }

    @Test
    void should_resend_with_same_idempotency_key_when_verification_after_timeout_does_not_find_the_sale() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).inScenario("timeout").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(jsonResponse(SALE, 201).withFixedDelay(2500)).willSetStateTo("second"));
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).inScenario("timeout").whenScenarioStateIs("second")
                .willReturn(jsonResponse(SALE, 201)));
        stubPos(get(urlPathEqualTo(pos("/api/integration/orders/GIFTHUB/" + orderId))).willReturn(jsonResponse(
                "{\"statusCode\":404,\"message\":\"Pedido no encontrado\"}", 404)));

        Operation op = receiveOrder(maria());
        drain();
        makeDue(op.id());
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", "/api/integration/orders")).hasSize(2)
                .allSatisfy(request -> assertThat(request.getHeader("Idempotency-Key")).isEqualTo("GIFTHUB:" + orderId));
    }

    @Test
    void should_end_dead_when_notify_exhausts_attempts_and_replay_only_reruns_notify() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(SALE, 201)));
        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/status"))).willReturn(status(503)));

        Operation op = receiveOrder(maria());
        for (int i = 0; i < 3; i++) {
            drain();
            makeDue(op.id());
        }
        Operation dead = reload(op.id());
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("attempts-exhausted");
        assertThat(dead.currentStep()).isEqualTo("NOTIFY_GIFTHUB");

        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/status")))
                .willReturn(okJson(giftHubOrder(orderId, "CONFIRMED", "PENDING", "DELIVERY"))));
        perform(MockMvcRequestBuilders.post("/api/admin/operations/{id}/replay", op.id()).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200);
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", "/api/integration/orders")).hasSize(1);
        assertThat(giftHubRequests("POST", "/orders/" + orderId + "/status")).hasSize(4)
                .allSatisfy(request -> assertThat(request.getHeader("Idempotency-Key")).isEqualTo("nexus:" + op.id() + ":CONFIRMED"));
    }

    @Test
    void should_cancel_pos_sale_when_order_was_cancelled_in_gifthub_before_confirmation() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(SALE, 201)));
        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/status"))).willReturn(jsonResponse(
                "{\"error\":\"La transición de estado no está permitida\",\"code\":\"TRANSITION_NOT_ALLOWED\"}", 409)));
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "CANCELLED", "PENDING", "DELIVERY"))));
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders/GIFTHUB/" + orderId + "/cancel")))
                .willReturn(okJson(SALE.replace("PENDING", "CANCELLED"))));

        Operation op = receiveOrder(maria());
        drain();

        Operation failed = reload(op.id());
        assertThat(failed.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(failed.lastError().code()).isEqualTo("gifthub-order-cancelled");
        assertThat(posRequests("POST", "/api/integration/orders/GIFTHUB/" + orderId + "/cancel")).hasSize(1);
    }

    private Operation receiveOrder(Map<String, Object> customer) {
        String delivery = UUID.randomUUID().toString();
        JsonNode received = sendSigned(delivery, orderCreated(delivery, orderId, customer), 200);
        assertThat(received.get("outcome").asString()).isEqualTo("ACCEPTED");
        return reload(UUID.fromString(received.get("operationId").asString()));
    }

    private String mapping(String type, String internalId) {
        return jdbc.sql("SELECT external_id FROM entity_mappings WHERE tenant_id = :tenant AND entity_type = :type AND internal_id = :internal")
                .param("tenant", tenant).param("type", type).param("internal", internalId).query(String.class).single();
    }
}
