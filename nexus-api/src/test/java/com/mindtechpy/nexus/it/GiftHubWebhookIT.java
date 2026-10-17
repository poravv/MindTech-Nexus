package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.infrastructure.connectors.gifthub.GiftHubWebhookSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Public GiftHub webhook: HMAC with rotation, replay window, dedupe by delivery, size limit, unknown connection. */
class GiftHubWebhookIT extends AbstractConnectorIT {

    private static final int MAX_BODY_BYTES = 256 * 1024;

    private UUID integration;

    @BeforeEach
    void setUpIntegration() {
        integration = orderIntegration();
    }

    @Test
    void should_answer_401_and_start_nothing_when_signature_is_invalid() {
        String body = orderCreated("d-1", UUID.randomUUID().toString(), maria());
        String now = String.valueOf(Instant.now().getEpochSecond());

        JsonNode problem = body(postWebhook(giftHubConnection, "d-1", body, GiftHubWebhookSignature.sign("otro-secreto", now, bytes(body)),
                now, 401));

        assertThat(problem.get("code").asString()).isEqualTo("invalid-signature");
        assertThat(operationsOfTenant()).isZero();
    }

    @Test
    void should_accept_a_signed_delivery_when_unsigned_requests_exceeded_the_rate_limit_of_the_connection() {
        String now = String.valueOf(Instant.now().getEpochSecond());
        for (int i = 0; i < 120; i++) {
            String spam = orderCreated("spam-" + i, UUID.randomUUID().toString(), maria());
            postWebhook(giftHubConnection, "spam-" + i, spam, "v1=00", now, 401);
        }

        JsonNode received = sendSigned("d-after-spam", orderCreated("d-after-spam", UUID.randomUUID().toString(), maria()), 200);

        assertThat(received.get("outcome").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void should_answer_401_when_timestamp_is_older_than_five_minutes() {
        String body = orderCreated("d-old", UUID.randomUUID().toString(), maria());
        String old = String.valueOf(Instant.now().getEpochSecond() - 301);

        postWebhook(giftHubConnection, "d-old", body, GiftHubWebhookSignature.sign(WEBHOOK_SECRET, old, bytes(body)), old, 401);
    }

    @Test
    void should_accept_when_one_of_several_rotation_signatures_is_valid() {
        String body = orderCreated("d-rot", UUID.randomUUID().toString(), maria());
        String now = String.valueOf(Instant.now().getEpochSecond());
        String header = GiftHubWebhookSignature.sign("secreto-nuevo-no-cargado", now, bytes(body)) + ", "
                + GiftHubWebhookSignature.sign(WEBHOOK_SECRET, now, bytes(body));

        JsonNode received = body(postWebhook(giftHubConnection, "d-rot", body, header, now, 200));

        assertThat(received.get("outcome").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void should_answer_200_without_new_operation_when_delivery_is_repeated() {
        String body = orderCreated("d-dup", UUID.randomUUID().toString(), maria());

        JsonNode first = sendSigned("d-dup", body, 200);
        JsonNode second = sendSigned("d-dup", body, 200);

        assertThat(first.get("outcome").asString()).isEqualTo("ACCEPTED");
        assertThat(second.get("outcome").asString()).isEqualTo("DUPLICATE");
        assertThat(operationsOfTenant()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_events WHERE connection_id = :c AND event_id = 'd-dup'")
                .param("c", giftHubConnection).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void should_acknowledge_ping_without_work() {
        JsonNode received = sendSigned("d-ping", toJson(Map.of("id", "d-ping", "type", "ping", "data", Map.of())), 200);

        assertThat(received.get("outcome").asString()).isEqualTo("IGNORED");
        assertThat(operationsOfTenant()).isZero();
    }

    @Test
    void should_answer_404_for_unknown_connection_and_413_for_oversized_body() {
        String body = orderCreated("d-x", UUID.randomUUID().toString(), maria());
        String now = String.valueOf(Instant.now().getEpochSecond());
        postWebhook(UUID.randomUUID(), "d-x", body, GiftHubWebhookSignature.sign(WEBHOOK_SECRET, now, bytes(body)), now, 404);

        String huge = "{\"type\":\"order.created\",\"pad\":\"" + "x".repeat(MAX_BODY_BYTES + 1) + "\"}";
        perform(MockMvcRequestBuilders.post("/api/v1/webhooks/gifthub/{id}", giftHubConnection).contentType(MediaType.APPLICATION_JSON)
                .header("X-GiftHub-Delivery", "d-huge").content(huge), 413);
    }

    @Test
    void should_ask_gifthub_to_retry_when_order_integration_does_not_take_work() {
        changeState(integration, admin, "DISABLED", 200);

        sendSigned("d-disabled", orderCreated("d-disabled", UUID.randomUUID().toString(), maria()), 429);

        assertThat(jdbc.sql("SELECT count(*) FROM inbound_events WHERE connection_id = :c AND event_id = 'd-disabled'")
                .param("c", giftHubConnection).query(Long.class).single()).isZero();
    }

    @Test
    void should_void_pos_sale_when_gifthub_cancels_an_order_whose_sale_exists() {
        String orderId = orderWithSale("PENDING");
        stubPosCancel(orderId, okJson(sale("CANCELLED")));

        Operation op = cancelledInGiftHub(orderId);

        assertThat(op.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(jsonOf(posRequests("POST", "/api/integration/orders/GIFTHUB/" + orderId + "/cancel").getFirst())
                .get("reason").asString()).isEqualTo("Pedido cancelado en GiftHub");
        assertThat(giftHubRequests("GET", "/orders/" + orderId)).isEmpty();
    }

    @Test
    void should_void_a_collected_pos_sale_when_gifthub_cancelled_and_refunded_the_order() {
        String orderId = orderWithSale("COMPLETED");
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "CANCELLED", "REFUNDED", "PICKUP"))));
        stubPosCancel(orderId, okJson(sale("CANCELLED")));

        Operation op = cancelledInGiftHub(orderId);

        assertThat(op.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", "/api/integration/orders/GIFTHUB/" + orderId + "/cancel")).hasSize(1);
    }

    @Test
    void should_end_dead_without_calling_the_pos_when_gifthub_did_not_refund_a_collected_sale() {
        String orderId = orderWithSale("COMPLETED");
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "CANCELLED", "PAID", "PICKUP"))));

        Operation op = cancelledInGiftHub(orderId);

        assertThat(op.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(op.deadReason()).isEqualTo("manual-reconciliation");
        assertThat(op.lastError().code()).isEqualTo("gifthub-payment-not-refunded");
        assertThat(posRequests("POST", "/api/integration/orders/GIFTHUB/" + orderId + "/cancel")).isEmpty();
    }

    @Test
    void should_end_dead_asking_for_a_credit_note_when_the_pos_refuses_an_invoiced_sale() {
        String orderId = orderWithSale("COMPLETED");
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "CANCELLED", "REFUNDED", "PICKUP"))));
        stubPosCancel(orderId, jsonResponse("{\"code\":\"invoiced\",\"message\":\"La venta está facturada: emití una nota de crédito\"}", 409));

        Operation op = cancelledInGiftHub(orderId);

        assertThat(op.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(op.deadReason()).isEqualTo("manual-reconciliation");
        assertThat(op.lastError().code()).isEqualTo("invoiced");
        assertThat(op.lastError().detail()).isEqualTo("Venta facturada: requiere nota de crédito en el POS");
        assertThat(op.attempts()).isEqualTo(1);
    }

    @Test
    void should_retry_the_void_when_the_pos_has_no_open_register_for_the_refund() {
        String orderId = orderWithSale("COMPLETED");
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "CANCELLED", "REFUNDED", "PICKUP"))));
        stubPosCancel(orderId, jsonResponse("{\"code\":\"cash-session-closed\",\"message\":\"No hay caja abierta\"}", 409));

        Operation op = cancelledInGiftHub(orderId);

        assertThat(op.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(op.lastError().code()).isEqualTo("cash-session-closed");
    }

    @Test
    void should_succeed_without_calling_the_pos_cancel_when_the_sale_is_already_cancelled() {
        String orderId = orderWithSale("CANCELLED");

        Operation op = cancelledInGiftHub(orderId);

        assertThat(op.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", "/api/integration/orders/GIFTHUB/" + orderId + "/cancel")).isEmpty();
    }

    @Test
    void should_retry_without_calling_the_pos_cancel_when_gifthub_is_down_for_a_collected_sale() {
        String orderId = orderWithSale("COMPLETED");
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(aResponse().withStatus(503)));

        Operation op = cancelledInGiftHub(orderId);

        assertThat(op.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(posRequests("POST", "/api/integration/orders/GIFTHUB/" + orderId + "/cancel")).isEmpty();
    }

    /** Creates the order through the webhook (POS sale PENDING, GiftHub CONFIRMED); the POS then reports {@code saleStatus}. */
    private String orderWithSale(String saleStatus) {
        String orderId = UUID.randomUUID().toString();
        stubPos(post(urlPathEqualTo(pos("/api/integration/customers/resolve"))).willReturn(okJson("{\"customerId\":\"c-1\"}")));
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(sale("PENDING"), 201)));
        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/status")))
                .willReturn(okJson(giftHubOrder(orderId, "CONFIRMED", "PENDING", "PICKUP"))));
        String delivery = "d-" + UUID.randomUUID();
        sendSigned(delivery, orderCreated(delivery, orderId, maria()), 200);
        drain();
        stubPos(get(urlPathEqualTo(pos("/api/integration/orders/GIFTHUB/" + orderId)))
                .willReturn(okJson(sale(saleStatus))));
        return orderId;
    }

    private void stubPosCancel(String orderId, ResponseDefinitionBuilder response) {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders/GIFTHUB/" + orderId + "/cancel"))).willReturn(response));
    }

    private Operation cancelledInGiftHub(String orderId) {
        String delivery = "d-" + UUID.randomUUID();
        String cancelled = toJson(Map.of("id", delivery, "type", "order.cancelled",
                "data", Map.of("order", Map.of("id", orderId, "status", "CANCELLED", "previous_status", "CONFIRMED"))));
        JsonNode received = sendSigned(delivery, cancelled, 200);
        assertThat(received.get("outcome").asString()).isEqualTo("ACCEPTED");
        drain();
        return reload(UUID.fromString(received.get("operationId").asString()));
    }

    private static String sale(String status) {
        return "{\"saleId\":\"s-9\",\"saleNumber\":\"SALE-9\",\"total\":1,\"status\":\"" + status + "\"}";
    }

    private long operationsOfTenant() {
        return jdbc.sql("SELECT count(*) FROM operations WHERE tenant_id = :t").param("t", tenant).query(Long.class).single();
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
