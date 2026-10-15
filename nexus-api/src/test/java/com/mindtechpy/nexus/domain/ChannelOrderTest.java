package com.mindtechpy.nexus.domain;

import com.mindtechpy.nexus.domain.order.ChannelOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GiftHub {@code data.order} → the product lines of the POS sale. */
class ChannelOrderTest {

    @Test
    void should_carry_gifthub_unit_price_on_every_item_and_topping_line() {
        ChannelOrder order = ChannelOrder.from(Map.of("id", "ord-1", "items", List.of(
                Map.of("external_id", "SKU-1001", "name", "Burger", "quantity", 2, "unit_price_pyg", 38000,
                        "toppings", List.of(Map.of("external_id", "SKU-2001", "name", "Cheddar", "quantity", 2, "unit_price_pyg", 2500))),
                Map.of("external_id", "SKU-3001", "name", "Gaseosa", "quantity", 1, "unit_price_pyg", 9000))));

        assertThat(order.saleLines()).containsExactly(
                new ChannelOrder.Line("SKU-1001", "Burger", new BigDecimal("2"), 38000L),
                new ChannelOrder.Line("SKU-2001", "Cheddar", new BigDecimal("2"), 2500L),
                new ChannelOrder.Line("SKU-3001", "Gaseosa", new BigDecimal("1"), 9000L));
    }

    @Test
    void should_leave_the_unit_price_empty_when_a_stored_order_has_none() {
        ChannelOrder order = ChannelOrder.from(Map.of("id", "ord-1", "items", List.of(
                Map.of("external_id", "SKU-1001", "name", "Burger", "quantity", 1))));

        assertThat(order.saleLines().getFirst().unitPrice()).isNull();
    }
}
