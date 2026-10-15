package com.mindtechpy.nexus.domain;

import com.mindtechpy.nexus.domain.order.OrderPayment;
import com.mindtechpy.nexus.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GiftHub {@code order.paid} → what the POS payment needs. */
class OrderPaymentTest {

    @Test
    void should_map_gifthub_methods_to_pos_methods_and_everything_else_to_other() {
        assertThat(OrderPayment.from(order("CASH")).posMethod()).isEqualTo("CASH");
        assertThat(OrderPayment.from(order("CARD")).posMethod()).isEqualTo("CARD");
        assertThat(OrderPayment.from(order("TRANSFER")).posMethod()).isEqualTo("TRANSFER");
        assertThat(OrderPayment.from(order("OTHER")).posMethod()).isEqualTo("OTHER");
        assertThat(OrderPayment.from(order(null)).posMethod()).isEqualTo("OTHER");
        assertThat(OrderPayment.from(order("BANCARD")).posMethod()).isEqualTo("OTHER");
    }

    @Test
    void should_prefer_the_method_of_data_payment_in_either_gifthub_vocabulary() {
        assertThat(OrderPayment.from(paid("OTHER", "QR", null)).posMethod()).isEqualTo("CARD");
        assertThat(OrderPayment.from(paid("CARD", "LINK_PAGO", null)).posMethod()).isEqualTo("CARD");
        assertThat(OrderPayment.from(paid("CARD", "TARJETA_POS", null)).posMethod()).isEqualTo("CARD");
        assertThat(OrderPayment.from(paid("OTHER", "EFECTIVO", null)).posMethod()).isEqualTo("CASH");
        assertThat(OrderPayment.from(paid("OTHER", "TRANSFERENCIA", null)).posMethod()).isEqualTo("TRANSFER");
        assertThat(OrderPayment.from(paid("CASH", "OTRO", null)).posMethod()).isEqualTo("OTHER");
        assertThat(OrderPayment.from(paid("OTHER", "CARD", null)).posMethod()).isEqualTo("CARD");
    }

    @Test
    void should_pay_qr_as_card_with_the_bancard_reference() {
        OrderPayment payment = OrderPayment.from(paid("OTHER", "QR", "  BCD-778812  "));

        assertThat(payment.posMethod()).isEqualTo("CARD");
        assertThat(payment.reference()).isEqualTo("BCD-778812");
    }

    @Test
    void should_cut_the_provider_reference_to_what_the_pos_keeps() {
        assertThat(OrderPayment.from(paid("CARD", "LINK_PAGO", "x".repeat(150))).reference()).hasSize(100);
    }

    @Test
    void should_fall_back_to_the_order_method_and_number_without_data_payment() {
        OrderPayment payment = OrderPayment.from(OrderPayment.payloadOf(order("TRANSFER"), Map.of()));

        assertThat(payment.posMethod()).isEqualTo("TRANSFER");
        assertThat(payment.reference()).isEqualTo("GiftHub #BH-1");
    }

    @Test
    void should_reference_the_gifthub_number_or_the_id_when_there_is_none() {
        assertThat(OrderPayment.from(order("CASH")).reference()).isEqualTo("GiftHub #BH-1");
        Map<String, Object> noNumber = order("CASH");
        noNumber.remove("number");
        assertThat(OrderPayment.from(noNumber).reference()).isEqualTo("GiftHub #ord-1");
    }

    @Test
    void should_keep_only_the_payment_fields_in_the_payload() {
        Map<String, Object> order = order("CASH");
        order.put("items", java.util.List.of());
        order.put("customer", Map.of("phone", "+595981123456"));

        Map<String, Object> payload = OrderPayment.payloadOf(order, Map.of("method", "QR", "reference", "BCD-1", "paid_at", "2026-10-15T12:00:00Z"));

        assertThat(payload.keySet()).containsExactly("id", "number", "payment_status", "payment_method", "total_pyg", "payment");
        assertThat(payload.get("payment")).isEqualTo(Map.of("method", "QR", "reference", "BCD-1"));
    }

    @Test
    void should_reject_an_order_without_a_valid_id() {
        Map<String, Object> order = order("CASH");
        order.put("id", "con espacios");

        assertThatThrownBy(() -> OrderPayment.from(order)).isInstanceOf(DomainException.class).hasMessageContaining("id");
    }

    private static Map<String, Object> paid(String orderMethod, String paymentMethod, String reference) {
        Map<String, Object> payment = new HashMap<>();
        payment.put("method", paymentMethod);
        payment.put("reference", reference);
        return OrderPayment.payloadOf(order(orderMethod), payment);
    }

    private static Map<String, Object> order(String method) {
        Map<String, Object> order = new HashMap<>();
        order.put("id", "ord-1");
        order.put("number", "BH-1");
        order.put("payment_status", "PAID");
        order.put("payment_method", method);
        order.put("total_pyg", 91000);
        return order;
    }
}
