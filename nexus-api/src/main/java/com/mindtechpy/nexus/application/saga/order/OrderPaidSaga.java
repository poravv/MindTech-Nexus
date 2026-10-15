package com.mindtechpy.nexus.application.saga.order;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.PosPort;
import com.mindtechpy.nexus.application.saga.ManualReconciliationRequired;
import com.mindtechpy.nexus.application.saga.Saga;
import com.mindtechpy.nexus.application.saga.StepContext;
import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.order.OrderPayment;

import java.util.Map;
import java.util.Optional;

import static com.mindtechpy.nexus.application.saga.order.CreateOrderSaga.CHANNEL;
import static java.lang.System.Logger.Level.INFO;

/**
 * {@code [NEXUS-ORDER]} GiftHub collected a channel order → the POS registers the payment and closes the sale
 * (single step {@code PAY_POS}: {@code POST /api/integration/orders/GIFTHUB/{orderId}/payments},
 * {@code Idempotency-Key = GIFTHUB:{orderId}:paid}). Order gate: it only runs once the sale exists, per
 * {@code CREATE_ORDER}'s result or, failing that, a lookup in the POS. Without a sale it finishes
 * {@code SKIPPED no-sale} only when {@code CREATE_ORDER} ended FAILED (a confirmed rejection, already compensated);
 * while that one is running, or DEAD waiting for a replay, it retries and in the end goes DEAD too, so the console
 * replays {@code CREATE_ORDER} and then this one. The real effect is checked on both sides:
 * the event must say {@code payment_status = PAID} (else {@code SKIPPED not-paid}) and the POS answer must leave
 * the sale {@code COMPLETED}. A sale already {@code COMPLETED} (idempotent retry, 409 {@code not-pending},
 * verification after a timeout) is success. Nothing is undone: the money was taken in GiftHub, so a POS rejection
 * ({@code amount-mismatch}, {@code sale-cancelled}, {@code insufficient-stock}, sale not found) ends DEAD for
 * manual reconciliation; a closed cash session is retried with backoff until the attempts run out.
 */
public class OrderPaidSaga implements Saga {

    static final String PAY_POS = "PAY_POS";
    static final String SKIPPED = "SKIPPED";
    static final String NOT_PENDING = "not-pending";
    private static final String COMPLETED = "COMPLETED";

    private static final System.Logger log = System.getLogger(OrderPaidSaga.class.getName());

    private final PosPort pos;
    private final OperationRepository operations;

    public OrderPaidSaga(PosPort pos, OperationRepository operations) {
        this.pos = pos;
        this.operations = operations;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.ORDER_PAID;
    }

    @Override
    public String firstStep() {
        return PAY_POS;
    }

    @Override
    public StepResult execute(StepContext context) {
        try {
            OrderEndpoints endpoints = OrderEndpoints.of(context);
            OrderPayment payment = OrderPayment.from(context.operation().payload());
            if (!payment.isPaid()) {
                return skipped(context, "not-paid", "GiftHub reporta payment_status " + payment.paymentStatus());
            }
            if (payment.totalPyg() == null) {
                return rejected("invalid-order", "El evento order.paid no trae total_pyg");
            }
            Operation created = operations.findByKey(context.operation().tenantId(), OperationKind.CREATE_ORDER,
                    payment.orderId()).orElse(null);
            if ((created == null || created.result() == null || created.result().get("saleId") == null)
                    && pos.findOrder(endpoints.pos(), endpoints.readTimeout(), CHANNEL, payment.orderId()).isEmpty()) {
                return saleMissing(context, created);
            }
            if (context.operation().needsVerification()) {
                Optional<PosPort.Order> completed = completedSale(endpoints, payment.orderId());
                if (completed.isPresent()) {
                    logStep(context, "verificación: la venta ya está cobrada en el POS");
                    return completed(payment, completed.get().saleId(), completed.get().saleNumber(), false);
                }
                logStep(context, "verificación: la venta sigue pendiente en el POS, se reenvía con la misma clave");
            }
            PosPort.PaidSale sale;
            try {
                sale = pos.payChannelOrder(endpoints.pos(), endpoints.readTimeout(), CHANNEL + ":" + payment.orderId() + ":paid",
                        CHANNEL, payment.orderId(), new PosPort.Payment(payment.posMethod(), payment.totalPyg(), payment.reference()));
            } catch (ConnectorException e) {
                if (!NOT_PENDING.equals(e.error().code())) {
                    throw e;
                }
                PosPort.Order completed = completedSale(endpoints, payment.orderId()).orElseThrow(() -> e);
                logStep(context, "el POS ya tenía la venta " + completed.saleNumber() + " cobrada");
                return completed(payment, completed.saleId(), completed.saleNumber(), false);
            }
            if (!COMPLETED.equals(sale.status())) {
                return new StepResult.Failed(ConnectorError.of(ErrorKind.UNCERTAIN, "pos-payment-not-applied",
                        "El POS respondió 200 pero la venta quedó " + sale.status()));
            }
            logStep(context, "venta POS " + sale.saleNumber() + " cobrada " + payment.posMethod() + " Gs. " + payment.totalPyg()
                    + (sale.invoiceQueued() ? ", factura encolada" : ""));
            return completed(payment, sale.saleId(), sale.saleNumber(), sale.invoiceQueued());
        } catch (ConnectorException e) {
            return new StepResult.Failed(e.error());
        }
    }

    /** Nothing to undo: GiftHub already took the money. A POS rejection is settled by a human. */
    @Override
    public void compensate(StepContext context, ConnectorError cause) {
        if (cause.kind() == ErrorKind.REJECTED) {
            throw new ManualReconciliationRequired("El pedido está pagado en GiftHub pero el POS no registró el cobro ("
                    + cause.code() + (cause.detail() == null ? "" : ": " + cause.detail()) + "): cobrar o anular la venta a mano");
        }
    }

    /**
     * No sale in the POS. CREATE_ORDER FAILED (rejected, the order cancelled in GiftHub): nothing to collect.
     * Anything else (not here yet, running, DEAD until a replay) may still produce it: retry.
     */
    private StepResult saleMissing(StepContext context, Operation created) {
        if (created != null && created.status() == OperationStatus.FAILED) {
            return skipped(context, "no-sale", "CREATE_ORDER terminó FAILED sin venta en el POS");
        }
        return new StepResult.Failed(ConnectorError.of(ErrorKind.TRANSIENT, "sale-pending",
                created == null ? "Todavía no llegó order.created" : "CREATE_ORDER sigue " + created.status() + " sin venta"));
    }

    private Optional<PosPort.Order> completedSale(OrderEndpoints endpoints, String orderId) {
        return pos.findOrder(endpoints.pos(), endpoints.readTimeout(), CHANNEL, orderId)
                .filter(sale -> COMPLETED.equals(sale.status()));
    }

    private static StepResult completed(OrderPayment payment, String saleId, String saleNumber, boolean invoiceQueued) {
        return new StepResult.Completed(saleId, Map.of("saleId", saleId, "saleNumber", saleNumber, "method", payment.posMethod(),
                "amount", payment.totalPyg(), "invoiceQueued", invoiceQueued));
    }

    private static StepResult skipped(StepContext context, String reason, String detail) {
        logStep(context, "omitido (" + reason + "): " + detail);
        return new StepResult.Completed(SKIPPED + ":" + reason, Map.of("outcome", SKIPPED, "reason", reason));
    }

    private static StepResult rejected(String code, String detail) {
        return new StepResult.Failed(ConnectorError.of(ErrorKind.REJECTED, code, detail));
    }

    private static void logStep(StepContext context, String message) {
        Operation op = context.operation();
        log.log(INFO, "[NEXUS-ORDER] op={0} tenant={1} step={2} {3}", op.id(), op.tenantId(), op.currentStep(), message);
    }
}
