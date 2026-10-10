package com.mindtechpy.nexus.domain.order;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An order received from a sales channel (GiftHub {@code data.order}, manual §7.3), reduced to what the POS
 * needs. The operation payload keeps GiftHub's own field names, so {@link #from} parses both the webhook and
 * the stored payload. {@code externalId} of an item or topping is null when the product was not synced by
 * Nexus. Amounts are guaraníes as GiftHub charged them; null in payloads stored before they were kept.
 */
public record ChannelOrder(String orderId, String number, String fulfillment, String paymentMethod, String notes,
                           Customer customer, Delivery delivery, List<Item> items, Long totalPyg, Long deliveryFeePyg,
                           Long discountPyg) {

    /** The POS accepts this alphabet for {@code externalRef} (it travels in its URLs). */
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9:_-]{1,100}$");

    public record Customer(String name, String phone) {
    }

    public record Delivery(String address, String reference) {
    }

    /** A product to sell: an item, or one of its toppings (GiftHub sends the topping quantity already multiplied). */
    public record Line(String externalId, String name, BigDecimal quantity) {
    }

    /** {@code options}: the chosen option groups as text (they are not products), null when there are none. */
    public record Item(String externalId, String name, BigDecimal quantity, String options, List<Line> toppings) {
    }

    /** @throws DomainException {@code invalid-order} when the id or the items are missing or malformed */
    public static ChannelOrder from(Map<String, Object> order) {
        String id = text(order, "id");
        if (id == null || !REF.matcher(id).matches()) {
            throw DomainException.invalid("invalid-order", "El pedido no trae un id válido");
        }
        if (!(order.get("items") instanceof List<?> rawItems) || rawItems.isEmpty()) {
            throw DomainException.invalid("invalid-order", "El pedido no trae ítems");
        }
        List<Item> items = new ArrayList<>();
        for (Object raw : rawItems) {
            Map<String, Object> item = map(raw);
            items.add(new Item(text(item, "external_id"), text(item, "name"), quantity(item.get("quantity")), options(item),
                    toppings(item)));
        }
        Map<String, Object> customer = map(order.get("customer"));
        Map<String, Object> delivery = map(order.get("delivery"));
        return new ChannelOrder(id, text(order, "number"), text(order, "fulfillment"), text(order, "payment_method"),
                text(order, "notes"),
                customer.isEmpty() ? null : new Customer(text(customer, "name"), text(customer, "phone")),
                delivery.isEmpty() ? null : new Delivery(text(delivery, "address"), text(delivery, "reference")),
                List.copyOf(items), amount(order, "total_pyg"), amount(order, "delivery_fee_pyg"), amount(order, "discount_pyg"));
    }

    /** The subset of a GiftHub order that {@link #from} reads, to store as the operation payload. */
    public static Map<String, Object> payloadOf(Map<String, Object> order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String key : List.of("id", "number", "fulfillment", "payment_method", "notes", "customer", "delivery", "items",
                "total_pyg", "delivery_fee_pyg", "discount_pyg")) {
            payload.put(key, order.get(key));
        }
        from(payload);
        return payload;
    }

    /** Every item and topping as a product line of the sale. */
    public List<Line> saleLines() {
        List<Line> lines = new ArrayList<>();
        for (Item item : items) {
            lines.add(new Line(item.externalId(), item.name(), item.quantity()));
            lines.addAll(item.toppings());
        }
        return lines;
    }

    public List<String> itemsWithoutCode() {
        return saleLines().stream().filter(line -> line.externalId() == null).map(line -> String.valueOf(line.name())).toList();
    }

    private static String options(Map<String, Object> item) {
        List<String> parts = new ArrayList<>();
        if (item.get("options") instanceof List<?> options) {
            for (Object raw : options) {
                Map<String, Object> option = map(raw);
                parts.add(text(option, "group") + ": " + (option.get("values") instanceof List<?> values
                        ? String.join(", ", values.stream().map(String::valueOf).toList()) : ""));
            }
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static List<Line> toppings(Map<String, Object> item) {
        List<Line> toppings = new ArrayList<>();
        if (item.get("toppings") instanceof List<?> raw) {
            for (Object value : raw) {
                Map<String, Object> topping = map(value);
                toppings.add(new Line(text(topping, "external_id"), text(topping, "name"), quantity(topping.get("quantity"))));
            }
        }
        return List.copyOf(toppings);
    }

    private static Long amount(Map<String, Object> order, String key) {
        return order.get(key) instanceof Number number ? number.longValue() : null;
    }

    private static BigDecimal quantity(Object value) {
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            quantity = BigDecimal.ZERO;
        }
        if (quantity.signum() <= 0) {
            throw DomainException.invalid("invalid-order", "Cantidad inválida en un ítem del pedido");
        }
        return quantity.stripTrailingZeros();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }
}
