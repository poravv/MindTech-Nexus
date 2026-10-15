package com.mindtechpy.nexus.domain.order;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The payment GiftHub reports in {@code order.paid} ({@code data.order}), reduced to what the POS needs to
 * register it on the channel sale. The operation payload keeps GiftHub's own field names, so {@link #from}
 * parses both the webhook and the stored payload. {@code totalPyg} is what GiftHub charged, in guaraníes.
 */
public record OrderPayment(String orderId, String number, String paymentStatus, String paymentMethod, Long totalPyg) {

    /** The POS accepts this alphabet for {@code externalRef} (it travels in its URLs). */
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9:_-]{1,100}$");
    private static final Set<String> POS_PAYMENT_METHODS = Set.of("CASH", "CARD", "TRANSFER");

    /** @throws DomainException {@code invalid-order} when the id is missing or malformed */
    public static OrderPayment from(Map<String, Object> order) {
        String id = text(order, "id");
        if (id == null || !REF.matcher(id).matches()) {
            throw DomainException.invalid("invalid-order", "El pedido no trae un id válido");
        }
        return new OrderPayment(id, text(order, "number"), text(order, "payment_status"), text(order, "payment_method"),
                order.get("total_pyg") instanceof Number number ? number.longValue() : null);
    }

    /** The subset of a GiftHub order that {@link #from} reads, to store as the operation payload. */
    public static Map<String, Object> payloadOf(Map<String, Object> order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String key : List.of("id", "number", "payment_status", "payment_method", "total_pyg")) {
            payload.put(key, order.get(key));
        }
        from(payload);
        return payload;
    }

    public boolean isPaid() {
        return "PAID".equals(paymentStatus);
    }

    /** POS payment method; a GiftHub method without POS equivalent, or none, travels as {@code OTHER}. */
    public String posMethod() {
        return paymentMethod != null && POS_PAYMENT_METHODS.contains(paymentMethod) ? paymentMethod : "OTHER";
    }

    /** What the POS keeps as the payment reference. */
    public String reference() {
        return "GiftHub #" + (number == null ? orderId : number);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }
}
