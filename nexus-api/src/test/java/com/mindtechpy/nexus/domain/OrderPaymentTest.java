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

        assertThat(OrderPayment.payloadOf(order).keySet()).containsExactly("id", "number", "payment_status", "payment_method", "total_pyg");
    }

    @Test
    void should_reject_an_order_without_a_valid_id() {
        Map<String, Object> order = order("CASH");
        order.put("id", "con espacios");

        assertThatThrownBy(() -> OrderPayment.from(order)).isInstanceOf(DomainException.class).hasMessageContaining("id");
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
