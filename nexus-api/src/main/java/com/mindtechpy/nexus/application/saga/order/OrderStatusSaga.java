package com.mindtechpy.nexus.application.saga.order;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.GiftHubPort;
import com.mindtechpy.nexus.application.port.out.PosPort;
import com.mindtechpy.nexus.application.saga.ManualReconciliationRequired;
import com.mindtechpy.nexus.application.saga.Saga;
import com.mindtechpy.nexus.application.saga.StepContext;
import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;

import java.util.Map;

import static java.lang.System.Logger.Level.INFO;

/**
 * {@code [NEXUS-ORDER]} keeps a channel order in sync after the sale exists. {@code payload.target}:
 * <ul>
 *   <li>{@code COMPLETED} (POS collected the sale): GiftHub paid, then forward one valid transition per step
 *   ({@code CONFIRMED → PREPARING → READY → [OUT_FOR_DELIVERY →] DELIVERED}); GiftHub has no shortcut.</li>
 *   <li>{@code CANCELLED} (POS voided the sale): GiftHub {@code CANCELLED}.</li>
 *   <li>{@code POS_CANCELLED} (GiftHub cancelled the order): void the pending POS sale.</li>
 * </ul>
 * Single step {@code SYNC}: it reads the current GiftHub order, applies the next action and repeats until
 * the target is reached, so a retry always starts from the real state. When the systems disagree (POS
 * collected an order cancelled in GiftHub, POS voided a paid or delivered order, GiftHub cancelled a sale
 * the POS already collected) nothing is forced: the operation ends DEAD for manual reconciliation.
 */
public class OrderStatusSaga implements Saga {

    static final String SYNC = "SYNC";

    private static final System.Logger log = System.getLogger(OrderStatusSaga.class.getName());

    private final PosPort pos;
    private final GiftHubPort giftHub;

    public OrderStatusSaga(PosPort pos, GiftHubPort giftHub) {
        this.pos = pos;
        this.giftHub = giftHub;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.ORDER_STATUS;
    }

    @Override
    public String firstStep() {
        return SYNC;
    }

    @Override
    public StepResult execute(StepContext context) {
        try {
            OrderEndpoints endpoints = OrderEndpoints.of(context);
            Map<String, Object> payload = context.operation().payload();
            String orderId = (String) payload.get("orderId");
            return switch ((String) payload.get("target")) {
                case "COMPLETED" -> completeInGiftHub(context, endpoints, orderId, payload);
                case "CANCELLED" -> cancelInGiftHub(context, endpoints, orderId);
                case "POS_CANCELLED" -> cancelInPos(context, endpoints, orderId);
                default -> throw new IllegalStateException("Destino desconocido: " + payload.get("target"));
            };
        } catch (ConnectorException e) {
            return new StepResult.Failed(e.error());
        }
    }

    private StepResult completeInGiftHub(StepContext context, OrderEndpoints endpoints, String orderId, Map<String, Object> payload) {
        GiftHubPort.Order order = current(endpoints, orderId);
        if ("CANCELLED".equals(order.status())) {
            return disagreement("gifthub-order-cancelled", "La venta se cobró en el POS pero el pedido está cancelado en GiftHub");
        }
        String saleNumber = (String) payload.get("saleNumber");
        String key = "nexus:" + context.operation().id() + ":";
        if (!"PAID".equals(order.paymentStatus())) {
            GiftHubPort.Order paid = giftHub.markPaid(endpoints.giftHub(), endpoints.readTimeout(), key + "PAID", orderId,
                    "Venta POS " + saleNumber, giftHubMethod((String) payload.get("paymentMethod")));
            if (!"PAID".equals(paid.paymentStatus())) {
                return notApplied("PAID");
            }
            logStep(context, "GiftHub pagado");
            return new StepResult.Next(SYNC, null, "PAID", null);
        }
        if ("DELIVERED".equals(order.status())) {
            return new StepResult.Completed("DELIVERED", null);
        }
        String next = forward(order.status(), "DELIVERY".equals(order.fulfillment()));
        GiftHubPort.Order moved = giftHub.setStatus(endpoints.giftHub(), endpoints.readTimeout(), key + next, orderId,
                new GiftHubPort.StatusChange(next, "Venta POS " + saleNumber + " cobrada", null, saleNumber));
        if (!next.equals(moved.status())) {
            return notApplied(next);
        }
        logStep(context, "GiftHub " + order.status() + " -> " + next);
        return "DELIVERED".equals(next) ? new StepResult.Completed(next, null) : new StepResult.Next(SYNC, null, next, null);
    }

    private StepResult cancelInGiftHub(StepContext context, OrderEndpoints endpoints, String orderId) {
        GiftHubPort.Order order = current(endpoints, orderId);
        if ("CANCELLED".equals(order.status())) {
            return new StepResult.Completed("CANCELLED", null);
        }
        if ("PAID".equals(order.paymentStatus()) || "DELIVERED".equals(order.status())) {
            return disagreement("gifthub-order-not-cancellable", "La venta se anuló en el POS pero el pedido de GiftHub está "
                    + ("PAID".equals(order.paymentStatus()) ? "pagado: reembolsar en el panel y cancelarlo" : "entregado"));
        }
        GiftHubPort.Order cancelled = giftHub.setStatus(endpoints.giftHub(), endpoints.readTimeout(),
                "nexus:" + context.operation().id() + ":CANCELLED", orderId,
                new GiftHubPort.StatusChange("CANCELLED", "Venta anulada en el POS", null, null));
        if (!"CANCELLED".equals(cancelled.status())) {
            return notApplied("CANCELLED");
        }
        logStep(context, "GiftHub CANCELLED");
        return new StepResult.Completed("CANCELLED", null);
    }

    private StepResult cancelInPos(StepContext context, OrderEndpoints endpoints, String orderId) {
        try {
            boolean found = pos.cancelOrder(endpoints.pos(), endpoints.readTimeout(), CreateOrderSaga.CHANNEL, orderId,
                    "Pedido cancelado en GiftHub").isPresent();
            logStep(context, found ? "venta del POS anulada" : "el POS no tiene la venta: nada que anular");
            return new StepResult.Completed(found ? "POS_CANCELLED" : "POS_NOT_FOUND", null);
        } catch (ConnectorException e) {
            if ("pos-order-not-pending".equals(e.error().code())) {
                return disagreement("pos-order-not-pending", "El pedido se canceló en GiftHub pero la venta del POS ya se cobró");
            }
            throw e;
        }
    }

    /** A rejection means GiftHub and the POS disagree: never forced, always reconciled by a human. */
    @Override
    public boolean compensate(StepContext context, ConnectorError cause) {
        if (cause.kind() == ErrorKind.REJECTED) {
            throw new ManualReconciliationRequired(cause.detail());
        }
        return false;
    }

    private GiftHubPort.Order current(OrderEndpoints endpoints, String orderId) {
        return giftHub.findOrder(endpoints.giftHub(), endpoints.readTimeout(), orderId)
                .orElseThrow(() -> new ConnectorException(ConnectorError.of(ErrorKind.REJECTED, "gifthub-order-not-found",
                        "El pedido " + orderId + " no existe en GiftHub")));
    }

    /** Next valid GiftHub transition towards DELIVERED (pickups skip OUT_FOR_DELIVERY). */
    static String forward(String status, boolean delivery) {
        return switch (status) {
            case "DRAFT" -> "PENDING";
            case "PENDING" -> "CONFIRMED";
            case "CONFIRMED" -> "PREPARING";
            case "PREPARING" -> "READY";
            case "READY" -> delivery ? "OUT_FOR_DELIVERY" : "DELIVERED";
            case "OUT_FOR_DELIVERY" -> "DELIVERED";
            default -> throw new ConnectorException(ConnectorError.of(ErrorKind.REJECTED, "gifthub-unknown-status",
                    "Estado de GiftHub desconocido: " + status));
        };
    }

    /** POS payment methods without a GiftHub equivalent (cheque, crédito) travel as OTHER. */
    private static String giftHubMethod(String posMethod) {
        return switch (posMethod == null ? "" : posMethod) {
            case "CASH", "CARD", "TRANSFER" -> posMethod;
            default -> "OTHER";
        };
    }

    private static StepResult disagreement(String code, String detail) {
        return new StepResult.Failed(ConnectorError.of(ErrorKind.REJECTED, code, detail));
    }

    private static StepResult notApplied(String expected) {
        return new StepResult.Failed(ConnectorError.of(ErrorKind.UNCERTAIN, "gifthub-change-not-applied",
                "GiftHub respondió 200 pero el pedido no quedó " + expected));
    }

    private static void logStep(StepContext context, String message) {
        Operation op = context.operation();
        log.log(INFO, "[NEXUS-ORDER] op={0} tenant={1} step={2} {3}", op.id(), op.tenantId(), op.currentStep(), message);
    }
}
