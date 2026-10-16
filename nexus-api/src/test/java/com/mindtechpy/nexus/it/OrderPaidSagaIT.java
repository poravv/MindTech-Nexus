package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code [NEXUS-ORDER]} ORDER_PAID: {@code order.paid} of GiftHub registers the payment on the POS channel sale and closes it. */
class OrderPaidSagaIT extends AbstractConnectorIT {

    private static final String SALE = """
            {"saleId":"sale-1","saleNumber":"SALE-00000042","total":91000,"status":"PENDING"}""";
    private static final String PAID_SALE = """
            {"saleId":"sale-1","saleNumber":"SALE-00000042","status":"COMPLETED","invoiceQueued":true}""";

    private String orderId;
    private String paymentsPath;

    @BeforeEach
    void setUpOrder() {
        orderIntegration();
        orderId = UUID.randomUUID().toString();
        paymentsPath = "/api/integration/orders/GIFTHUB/" + orderId + "/payments";
        stubPos(post(urlPathEqualTo(pos("/api/integration/customers/resolve")))
                .willReturn(jsonResponse("{\"customerId\":\"c-maria\",\"name\":\"María López\",\"created\":true}", 201)));
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(SALE, 201)));
        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + orderId + "/status")))
                .willReturn(okJson(giftHubOrder(orderId, "CONFIRMED", "PENDING", "DELIVERY"))));
    }

    @Test
    void should_register_the_payment_once_in_the_pos_when_gifthub_reports_the_order_paid() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(okJson(PAID_SALE)));
        createOrder();

        Operation op = receivePaid(UUID.randomUUID().toString(), "CASH");
        drain();

        Operation done = reload(op.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("saleNumber", "SALE-00000042").containsEntry("method", "CASH")
                .containsEntry("amount", 91000).containsEntry("invoiceQueued", true);
        var payments = posRequests("POST", paymentsPath);
        assertThat(payments).hasSize(1);
        assertThat(payments.getFirst().getHeader("Idempotency-Key")).isEqualTo("GIFTHUB:" + orderId + ":paid");
        assertThat(payments.getFirst().getHeader("X-Api-Key")).isEqualTo("pv_live_test");
        JsonNode body = jsonOf(payments.getFirst());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("method", "amount", "reference");
        assertThat(body.get("method").asString()).isEqualTo("CASH");
        assertThat(body.get("amount").asLong()).isEqualTo(91000);
        assertThat(body.get("reference").asString()).isEqualTo("GiftHub #BH-7Q2K");
    }

    @Test
    void should_send_the_bancard_reference_and_card_when_gifthub_reports_a_qr_payment() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(okJson(PAID_SALE)));
        createOrder();
        String delivery = UUID.randomUUID().toString();
        sendSigned(delivery, orderPaid(delivery, "OTHER", "QR", "BCD-778812", Map.of()), 200);
        drain();

        JsonNode body = jsonOf(posRequests("POST", paymentsPath).getFirst());
        assertThat(body.get("method").asString()).isEqualTo("CARD");
        assertThat(body.get("reference").asString()).isEqualTo("BCD-778812");
    }

    @Test
    void should_call_the_pos_once_when_gifthub_delivers_the_same_paid_event_again() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(okJson(PAID_SALE)));
        createOrder();
        String delivery = UUID.randomUUID().toString();
        Operation op = receivePaid(delivery, "TRANSFER");
        drain();

        assertThat(sendSigned(delivery, orderPaid(delivery, "TRANSFER"), 200).get("outcome").asString()).isEqualTo("DUPLICATE");
        JsonNode redelivered = sendSigned(UUID.randomUUID().toString(), orderPaid(UUID.randomUUID().toString(), "TRANSFER"), 200);
        assertThat(redelivered.get("operationId").asString()).isEqualTo(op.id().toString());
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", paymentsPath)).hasSize(1);
    }

    @Test
    void should_wait_for_the_sale_and_pay_it_when_paid_arrives_before_the_order_is_created() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(okJson(PAID_SALE)));

        Operation paid = receivePaid(UUID.randomUUID().toString(), "CARD");
        drain();
        Operation waiting = reload(paid.id());
        assertThat(waiting.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(waiting.lastError().code()).isEqualTo("sale-pending");
        assertThat(posRequests("POST", paymentsPath)).isEmpty();

        createOrder();
        makeDue(paid.id());
        drain();

        assertThat(reload(paid.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", paymentsPath)).hasSize(1);
        assertThat(jsonOf(posRequests("POST", paymentsPath).getFirst()).get("method").asString()).isEqualTo("CARD");
    }

    @Test
    void should_skip_without_calling_the_pos_when_the_order_was_rejected_and_has_no_sale() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(
                "{\"code\":\"insufficient-stock\",\"message\":\"Stock insuficiente\"}", 422)));
        stubGiftHub(get(urlPathEqualTo(gh("/orders/" + orderId))).willReturn(okJson(giftHubOrder(orderId, "PENDING", "PENDING", "DELIVERY"))));
        createOrder();

        Operation paid = receivePaid(UUID.randomUUID().toString(), "CASH");
        drain();

        Operation done = reload(paid.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("outcome", "SKIPPED").containsEntry("reason", "no-sale");
        assertThat(posRequests("POST", paymentsPath)).isEmpty();
    }

    @Test
    void should_end_dead_for_manual_reconciliation_when_the_order_was_rejected_without_cancelling_it_in_gifthub() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).willReturn(jsonResponse(
                "{\"type\":\"about:blank\",\"status\":400,\"code\":\"invalid-branch\",\"detail\":\"Sucursal inexistente\"}", 400)));
        createOrder();

        Operation paid = receivePaid(UUID.randomUUID().toString(), "CASH");
        drain();

        Operation dead = reload(paid.id());
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("manual-reconciliation");
        assertThat(dead.lastError().code()).isEqualTo("sale-rejected");
        assertThat(posRequests("POST", paymentsPath)).isEmpty();
    }

    @Test
    void should_succeed_when_the_pos_already_closed_the_sale_and_answers_not_pending() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(jsonResponse(
                "{\"type\":\"about:blank\",\"status\":409,\"code\":\"not-pending\",\"detail\":\"La venta no está pendiente\"}", 409)));
        stubPos(get(urlPathEqualTo(pos("/api/integration/orders/GIFTHUB/" + orderId)))
                .willReturn(okJson(SALE.replace("PENDING", "COMPLETED"))));
        createOrder();

        Operation paid = receivePaid(UUID.randomUUID().toString(), "CASH");
        drain();

        Operation done = reload(paid.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("saleNumber", "SALE-00000042");
        assertThat(posRequests("GET", "/api/integration/orders/GIFTHUB/" + orderId)).hasSize(1);
    }

    @Test
    void should_end_dead_for_manual_reconciliation_when_the_pos_rejects_the_amount() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(jsonResponse(
                "{\"type\":\"about:blank\",\"status\":409,\"code\":\"amount-mismatch\",\"detail\":\"El monto no coincide con el total\"}", 409)));
        createOrder();

        Operation paid = receivePaid(UUID.randomUUID().toString(), "CASH");
        drain();

        Operation dead = reload(paid.id());
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("manual-reconciliation");
        assertThat(dead.lastError().code()).isEqualTo("amount-mismatch");
        assertThat(dead.lastError().detail()).contains("El monto no coincide");
        assertThat(posRequests("POST", paymentsPath)).hasSize(1);
        assertThat(posRequests("POST", "/api/integration/orders/GIFTHUB/" + orderId + "/cancel")).isEmpty();
    }

    @Test
    void should_retry_with_the_same_key_and_close_the_sale_once_the_cash_session_opens() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).inScenario("caja").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(jsonResponse("{\"type\":\"about:blank\",\"status\":409,\"code\":\"cash-session-closed\",\"detail\":\"Caja cerrada\"}", 409))
                .willSetStateTo("abierta"));
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).inScenario("caja").whenScenarioStateIs("abierta").willReturn(okJson(PAID_SALE)));
        createOrder();

        Operation paid = receivePaid(UUID.randomUUID().toString(), "CASH");
        drain();
        Operation retrying = reload(paid.id());
        assertThat(retrying.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(retrying.lastError().code()).isEqualTo("cash-session-closed");
        makeDue(paid.id());
        drain();

        assertThat(reload(paid.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", paymentsPath)).hasSize(2)
                .allSatisfy(request -> assertThat(request.getHeader("Idempotency-Key")).isEqualTo("GIFTHUB:" + orderId + ":paid"));
    }

    @Test
    void should_read_the_sale_instead_of_resending_when_the_payment_post_got_a_502_after_the_pos_took_it() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(jsonResponse("<html>Bad Gateway</html>", 502)));
        stubPos(get(urlPathEqualTo(pos("/api/integration/orders/GIFTHUB/" + orderId)))
                .willReturn(okJson(SALE.replace("PENDING", "COMPLETED"))));
        createOrder();

        Operation paid = receivePaid(UUID.randomUUID().toString(), "CASH");
        drain();
        Operation retrying = reload(paid.id());
        assertThat(retrying.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(retrying.lastError().kind()).isEqualTo(ErrorKind.UNCERTAIN);
        assertThat(retrying.lastError().code()).isEqualTo("pos-http-502");
        makeDue(paid.id());
        drain();

        Operation done = reload(paid.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("saleNumber", "SALE-00000042");
        assertThat(posRequests("POST", paymentsPath)).hasSize(1);
    }

    @Test
    void should_give_the_pos_customer_the_invoice_data_before_registering_the_payment() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(okJson(PAID_SALE)));
        createOrder();
        String delivery = UUID.randomUUID().toString();
        sendSigned(delivery, orderPaidWithInvoice(delivery, "80012345", "6"), 200);
        drain();

        List<LoggedRequest> calls = POS.findAll(anyRequestedFor(anyUrl()));
        int withRuc = indexOf(calls, request -> request.getUrl().endsWith("/customers/resolve") && "80012345".equals(jsonOf(request).path("ruc").asString(null)));
        int payment = indexOf(calls, request -> request.getUrl().endsWith(paymentsPath));
        assertThat(withRuc).isNotNegative().isLessThan(payment);
        JsonNode body = jsonOf(calls.get(withRuc));
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("phoneE164", "name", "ruc", "dv", "businessName");
        assertThat(body.get("phoneE164").asString()).isEqualTo("+595981123456");
        assertThat(body.get("ruc").asString()).isEqualTo("80012345");
        assertThat(body.get("dv").asString()).isEqualTo("6");
        assertThat(body.get("businessName").asString()).isEqualTo("Comercial López S.A.");
        JsonNode invoice = jsonOf(calls.get(payment)).get("invoice");
        assertThat(invoice.propertyNames()).containsExactlyInAnyOrder("ruc", "dv", "businessName");
        assertThat(invoice.get("ruc").asString()).isEqualTo("80012345");
        assertThat(invoice.get("dv").asString()).isEqualTo("6");
        assertThat(invoice.get("businessName").asString()).isEqualTo("Comercial López S.A.");
    }

    @Test
    void should_invoice_each_sale_to_its_own_ruc_when_two_orders_of_the_same_phone_ask_for_different_rucs() {
        stubPos(post(urlPathMatching(pos("/api/integration/orders/GIFTHUB/[^/]+/payments"))).willReturn(okJson(PAID_SALE)));
        String first = orderId;
        createOrder();
        String second = UUID.randomUUID().toString();
        orderId = second;
        stubGiftHub(post(urlPathEqualTo(gh("/orders/" + second + "/status")))
                .willReturn(okJson(giftHubOrder(second, "CONFIRMED", "PENDING", "DELIVERY"))));
        stubPos(post(urlPathEqualTo(pos("/api/integration/orders"))).withRequestBody(containing(second))
                .willReturn(jsonResponse(SALE.replace("sale-1", "sale-2").replace("00000042", "00000043"), 201)));
        createOrder();

        String delivery = UUID.randomUUID().toString();
        sendSigned(delivery, orderPaidWithInvoice(delivery, "80022222", "2"), 200);
        orderId = first;
        delivery = UUID.randomUUID().toString();
        sendSigned(delivery, orderPaidWithInvoice(delivery, "80011111", "1"), 200);
        drain();

        assertThat(jsonOf(posRequests("POST", "/api/integration/orders/GIFTHUB/" + first + "/payments").getFirst())
                .path("invoice").path("ruc").asString()).isEqualTo("80011111");
        assertThat(jsonOf(posRequests("POST", "/api/integration/orders/GIFTHUB/" + second + "/payments").getFirst())
                .path("invoice").path("ruc").asString()).isEqualTo("80022222");
    }

    @Test
    void should_still_register_the_payment_when_the_pos_rejects_the_ruc() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/customers/resolve"))).withRequestBody(containing("\"ruc\""))
                .willReturn(jsonResponse("{\"code\":\"invalid-ruc\",\"message\":\"El DV no corresponde al RUC\"}", 400)));
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(okJson(PAID_SALE)));
        createOrder();
        String delivery = UUID.randomUUID().toString();
        JsonNode received = sendSigned(delivery, orderPaidWithInvoice(delivery, "80099999", "7"), 200);
        drain();

        Operation done = reload(UUID.fromString(received.get("operationId").asString()));
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("saleNumber", "SALE-00000042");
        assertThat(posRequests("POST", paymentsPath)).hasSize(1);
        assertThat(jsonOf(posRequests("POST", paymentsPath).getFirst()).has("invoice")).isFalse();
    }

    @Test
    void should_still_register_the_payment_when_another_pos_customer_already_has_the_ruc() {
        stubPos(post(urlPathEqualTo(pos("/api/integration/customers/resolve"))).withRequestBody(containing("\"ruc\""))
                .willReturn(jsonResponse("{\"type\":\"about:blank\",\"status\":409,\"code\":\"ruc-in-use\","
                        + "\"detail\":\"Otro cliente ya tiene ese RUC\"}", 409)));
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(okJson(PAID_SALE)));
        createOrder();
        String delivery = UUID.randomUUID().toString();
        JsonNode received = sendSigned(delivery, orderPaidWithInvoice(delivery, "80077777", "1"), 200);
        drain();

        assertThat(reload(UUID.fromString(received.get("operationId").asString())).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("POST", paymentsPath)).hasSize(1);
        assertThat(jsonOf(posRequests("POST", paymentsPath).getFirst()).path("invoice").path("ruc").asString()).isEqualTo("80077777");
    }

    @Test
    void should_not_touch_the_pos_customer_when_the_paid_order_has_no_invoice() {
        stubPos(post(urlPathEqualTo(pos(paymentsPath))).willReturn(okJson(PAID_SALE)));
        createOrder();
        int resolvesAtCreation = posRequests("POST", "/api/integration/customers/resolve").size();

        receivePaid(UUID.randomUUID().toString(), "CASH");
        drain();

        assertThat(posRequests("POST", "/api/integration/customers/resolve")).hasSize(resolvesAtCreation);
        assertThat(posRequests("POST", paymentsPath)).hasSize(1);
    }

    private void createOrder() {
        String delivery = UUID.randomUUID().toString();
        sendSigned(delivery, orderCreated(delivery, orderId, maria()), 200);
        drain();
    }

    private Operation receivePaid(String delivery, String method) {
        JsonNode received = sendSigned(delivery, orderPaid(delivery, method), 200);
        assertThat(received.get("outcome").asString()).isEqualTo("ACCEPTED");
        return reload(UUID.fromString(received.get("operationId").asString()));
    }

    /** {@code order.paid} as GiftHub sends it: the order with its payment state plus the {@code payment} block. */
    private String orderPaid(String deliveryId, String method) {
        return orderPaid(deliveryId, method, method, null, Map.of());
    }

    /** {@code order.paid} with "Incluir datos del cliente": the customer and the invoice data they gave. */
    private String orderPaidWithInvoice(String deliveryId, String ruc, String dv) {
        return orderPaid(deliveryId, "CASH", "CASH", null, Map.of("customer", maria(),
                "invoice", Map.of("requested", true, "ruc", ruc, "dv", dv, "legal_name", "Comercial López S.A.")));
    }

    private static int indexOf(List<LoggedRequest> requests, Predicate<LoggedRequest> matches) {
        for (int i = 0; i < requests.size(); i++) {
            if (matches.test(requests.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private String orderPaid(String deliveryId, String method, String paidWith, String reference, Map<String, Object> extra) {
        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", "BH-7Q2K");
        order.put("status", "CONFIRMED");
        order.put("payment_status", "PAID");
        order.put("payment_method", method);
        order.put("fulfillment", "DELIVERY");
        order.put("subtotal_pyg", 81000);
        order.put("discount_pyg", 0);
        order.put("delivery_fee_pyg", 10000);
        order.put("total_pyg", 91000);
        order.put("items", List.of(Map.of("external_id", "SKU-1001", "name", "Hamburguesa doble", "quantity", 2)));
        order.put("created_at", "2026-10-08T14:02:58.000Z");
        order.putAll(extra);
        Map<String, Object> payment = new HashMap<>();
        payment.put("method", paidWith);
        payment.put("reference", reference);
        payment.put("paid_at", "2026-10-08T14:20:00.000Z");
        return toJson(Map.of("id", deliveryId, "type", "order.paid", "created_at", "2026-10-08T14:20:01.000Z",
                "data", Map.of("order", order, "payment", payment)));
    }
}
