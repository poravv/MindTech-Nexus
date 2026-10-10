package com.mindtechpy.nexus.it;

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
        String orderId = UUID.randomUUID().toString();
        stubPos(post(urlPathEqualTo(pos("/api/integration/customers/resolve"))).willReturn(okJson("{\"customerId\":\"c-1\"}")));
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders")))
                .willReturn(jsonResponse("{\"saleId\":\"s-9\",\"saleNumber\":\"SALE-9\",\"total\":1,\"status\":\"PENDING\"}", 201)));
        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/status")))
                .willReturn(okJson(giftHubOrder(orderId, "CONFIRMED", "PENDING", "PICKUP"))));
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders/GIFTHUB/" + orderId + "/cancel")))
                .willReturn(okJson("{\"saleId\":\"s-9\",\"saleNumber\":\"SALE-9\",\"total\":1,\"status\":\"CANCELLED\"}")));
        sendSigned("d-c1", orderCreated("d-c1", orderId, maria()), 200);
        drain();

        String cancelled = toJson(Map.of("id", "d-c2", "type", "order.cancelled",
                "data", Map.of("order", Map.of("id", orderId, "status", "CANCELLED", "previous_status", "CONFIRMED"))));
        JsonNode received = sendSigned("d-c2", cancelled, 200);
        drain();

        assertThat(received.get("outcome").asString()).isEqualTo("ACCEPTED");
        assertThat(reload(UUID.fromString(received.get("operationId").asString())).status().name()).isEqualTo("SUCCEEDED");
        assertThat(jsonOf(posRequests("POST", "/api/integration/orders/GIFTHUB/" + orderId + "/cancel").getFirst())
                .get("reason").asString()).isEqualTo("Pedido cancelado en GiftHub");
    }

    private long operationsOfTenant() {
        return jdbc.sql("SELECT count(*) FROM operations WHERE tenant_id = :t").param("t", tenant).query(Long.class).single();
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
