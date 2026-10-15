package com.mindtechpy.nexus.domain.order;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The payment GiftHub reports in {@code order.paid} ({@code data.order} and {@code data.payment}), reduced to what
 * the POS needs to register it on the channel sale. The operation payload keeps GiftHub's own field names
 * ({@code data.payment} under {@code payment}), so {@link #from} parses both the webhook and the stored payload.
 * {@code totalPyg} is what GiftHub charged, in guaraníes. {@code paidWith}/{@code paidReference} come from
 * {@code data.payment}: null when it is missing (payloads stored before it was kept).
 */
public record OrderPayment(String orderId, String number, String paymentStatus, String paymentMethod, Long totalPyg,
                           String paidWith, String paidReference) {

    /** The POS accepts this alphabet for {@code externalRef} (it travels in its URLs). */
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9:_-]{1,100}$");
    private static final int MAX_REFERENCE = 100;
    /** GiftHub methods, in its API vocabulary and its own (EFECTIVO…), to the POS ones. */
    private static final Map<String, String> POS_METHODS = Map.of("CASH", "CASH", "CARD", "CARD", "TRANSFER", "TRANSFER",
            "EFECTIVO", "CASH", "TRANSFERENCIA", "TRANSFER", "TARJETA_POS", "CARD", "LINK_PAGO", "CARD", "QR", "CARD");

    /** @throws DomainException {@code invalid-order} when the id is missing or malformed */
    public static OrderPayment from(Map<String, Object> order) {
        String id = text(order, "id");
        if (id == null || !REF.matcher(id).matches()) {
            throw DomainException.invalid("invalid-order", "El pedido no trae un id válido");
        }
        Map<String, Object> payment = map(order.get("payment"));
        return new OrderPayment(id, text(order, "number"), text(order, "payment_status"), text(order, "payment_method"),
                order.get("total_pyg") instanceof Number number ? number.longValue() : null,
                text(payment, "method"), text(payment, "reference"));
    }

    /** The subset of a GiftHub order and its {@code data.payment} (empty when absent) that {@link #from} reads. */
    public static Map<String, Object> payloadOf(Map<String, Object> order, Map<String, Object> payment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String key : List.of("id", "number", "payment_status", "payment_method", "total_pyg")) {
            payload.put(key, order.get(key));
        }
        Map<String, Object> paid = new LinkedHashMap<>();
        paid.put("method", payment.get("method"));
        paid.put("reference", payment.get("reference"));
        payload.put("payment", paid);
        from(payload);
        return payload;
    }

    public boolean isPaid() {
        return "PAID".equals(paymentStatus);
    }

    /** POS payment method from {@code data.payment.method}, else the order's; none or unknown travels as {@code OTHER}. */
    public String posMethod() {
        String method = paidWith != null ? paidWith : paymentMethod;
        return method == null ? "OTHER" : POS_METHODS.getOrDefault(method, "OTHER");
    }

    /** What the POS keeps as the payment reference: the provider's (Bancard ticket) or the GiftHub order number. */
    public String reference() {
        if (paidReference != null) {
            return paidReference.length() <= MAX_REFERENCE ? paidReference : paidReference.substring(0, MAX_REFERENCE);
        }
        return "GiftHub #" + (number == null ? orderId : number);
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
