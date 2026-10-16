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
import com.mindtechpy.nexus.domain.order.InvoiceData;
import com.mindtechpy.nexus.domain.order.OrderPayment;
import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.Map;
import java.util.Optional;

import static com.mindtechpy.nexus.application.saga.order.CreateOrderSaga.CHANNEL;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * {@code [NEXUS-ORDER]} GiftHub collected a channel order → the POS registers the payment and closes the sale.
 * {@code APPLY_INVOICE_DATA} (best effort, never blocks): when the customer asked for an invoice ({@code invoice.requested}
 * with RUC, DV and legal name) the POS customer of the order's phone gets them ({@code POST /api/integration/customers/resolve},
 * idempotent) so its record is useful for future orders. Any failure logs a WARN and goes on to the payment; a POS
 * {@code invalid-ruc} is remembered so the payment does not carry that RUC; no invoice requested, an order without an
 * E.164 phone or a sale on the connection's {@code defaultCustomerId} skip it, so the generic customer never gets a RUC.
 * {@code PAY_POS}: {@code POST /api/integration/orders/GIFTHUB/{orderId}/payments},
 * {@code Idempotency-Key = GIFTHUB:{orderId}:paid}, with the order's own invoice data (well formed and not refused by the
 * POS) in the body: the POS freezes it on the sale, so a customer shared by several orders, or a replay resuming at
 * this step, never decides which RUC is invoiced; without it the invoice is consumidor final. Order gate: it only runs
 * once the sale exists, per {@code CREATE_ORDER}'s result or, failing that, a lookup in the POS. Without a sale it finishes
 * {@code SKIPPED no-sale} only when {@code CREATE_ORDER} ended FAILED with {@code result.compensated} (the order
 * cancelled in GiftHub); FAILED without it ends DEAD {@code manual-reconciliation} ({@code sale-rejected}); while
 * it is running, or DEAD waiting for a replay, it retries and in the end goes DEAD too, so the console replays
 * {@code CREATE_ORDER} and then this one. The real effect is checked on both sides:
 * the event must say {@code payment_status = PAID} (else {@code SKIPPED not-paid}) and the POS answer must leave
 * the sale {@code COMPLETED}. A sale already {@code COMPLETED} (idempotent retry, 409 {@code not-pending}) is
 * success; when the verification after an uncertain attempt finds it {@code COMPLETED}, the same-key payment is
 * still sent once so the POS enqueues the invoice if that attempt left it missing (200 or {@code not-pending} =
 * success; any other rejection of a sale already paid = success with a WARN; a transient failure retries).
 * Nothing is undone: the money was taken in GiftHub, so a POS rejection ({@code amount-mismatch},
 * {@code sale-cancelled}, {@code insufficient-stock}, sale not found) ends DEAD for manual reconciliation; a closed
 * cash session is retried with backoff until the attempts run out.
 */
public class OrderPaidSaga implements Saga {

    static final String APPLY_INVOICE_DATA = "APPLY_INVOICE_DATA";
    static final String PAY_POS = "PAY_POS";
    static final String SKIPPED = "SKIPPED";
    static final String NOT_PENDING = "not-pending";
    private static final String COMPLETED = "COMPLETED";
    private static final String INVALID_RUC = "invalid-ruc";
    private static final String INVOICE_REFUSED = "invoiceRefused";

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
        return APPLY_INVOICE_DATA;
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
            return switch (context.step()) {
                case APPLY_INVOICE_DATA -> applyInvoiceData(context, endpoints, payment);
                case PAY_POS -> payPos(context, endpoints, payment);
                default -> throw new IllegalStateException("Paso desconocido: " + context.step());
            };
        } catch (ConnectorException e) {
            return new StepResult.Failed(e.error());
        }
    }

    private StepResult applyInvoiceData(StepContext context, OrderEndpoints endpoints, OrderPayment payment) {
        InvoiceData invoice;
        try {
            invoice = InvoiceData.from(context.operation().payload().get("invoice"));
        } catch (DomainException e) {
            return invoiceWithoutRuc(context, e.getMessage());
        }
        if (invoice == null) {
            return new StepResult.Next(PAY_POS, null, null, null);
        }
        String phone = payment.customer() == null ? null : payment.customer().phone();
        String saleCustomer = saleCustomerId(context, payment.orderId());
        boolean onDefaultCustomer = saleCustomer != null
                && saleCustomer.equals(String.valueOf(endpoints.pos().connection().settings().get("defaultCustomerId")));
        if (phone == null || !CreateOrderSaga.E164.matcher(phone).matches() || onDefaultCustomer) {
            warn(context, "factura pedida con la venta en el cliente por defecto: no se le pone el RUC, la venta se factura con los datos del pedido");
            return new StepResult.Next(PAY_POS, null, null, null);
        }
        String name = payment.customer().name() == null ? "Cliente GiftHub" : payment.customer().name();
        String customerId;
        try {
            customerId = pos.resolveCustomer(endpoints.pos(), endpoints.readTimeout(), phone,
                    name.length() <= 100 ? name : name.substring(0, 100), invoice);
        } catch (ConnectorException e) {
            if (INVALID_RUC.equals(e.error().code())) {
                return invoiceWithoutRuc(context, INVALID_RUC + ": " + e.error().detail());
            }
            warn(context, "el cliente POS no se actualizó con los datos de factura (" + e.error().kind() + ":" + e.error().code()
                    + "): la venta igual se factura con ellos");
            return new StepResult.Next(PAY_POS, null, null, null);
        }
        logStep(context, "datos de factura en el cliente POS " + customerId);
        return new StepResult.Next(PAY_POS, null, customerId, null);
    }

    private StepResult payPos(StepContext context, OrderEndpoints endpoints, OrderPayment payment) {
        Operation created = operations.findByKey(context.operation().tenantId(), OperationKind.CREATE_ORDER,
                payment.orderId()).orElse(null);
        if ((created == null || created.result() == null || created.result().get("saleId") == null)
                && pos.findOrder(endpoints.pos(), endpoints.readTimeout(), CHANNEL, payment.orderId()).isEmpty()) {
            return saleMissing(context, created);
        }
        Optional<PosPort.Order> verified = Optional.empty();
        if (context.operation().needsVerification()) {
            verified = completedSale(endpoints, payment.orderId());
            logStep(context, verified.isPresent()
                    ? "verificación: la venta ya está cobrada en el POS, se repite el pago con la misma clave para que complete la factura"
                    : "verificación: la venta sigue pendiente en el POS, se reenvía con la misma clave");
        }
        PosPort.PaidSale sale;
        try {
            sale = pos.payChannelOrder(endpoints.pos(), endpoints.readTimeout(), CHANNEL + ":" + payment.orderId() + ":paid",
                    CHANNEL, payment.orderId(), new PosPort.Payment(payment.posMethod(), payment.totalPyg(), payment.reference(),
                            saleInvoice(context.operation())));
        } catch (ConnectorException e) {
            if (verified.isPresent() && e.error().kind() == ErrorKind.REJECTED && !NOT_PENDING.equals(e.error().code())) {
                warn(context, "la venta está cobrada pero el POS rechazó repetir el pago (" + e.error().code()
                        + "): revisar que tenga factura");
                return completed(payment, verified.get().saleId(), verified.get().saleNumber(), false);
            }
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
    }

    /** Nothing to undo: GiftHub already took the money. A POS rejection is settled by a human. */
    @Override
    public boolean compensate(StepContext context, ConnectorError cause) {
        if (cause.kind() == ErrorKind.REJECTED) {
            throw new ManualReconciliationRequired("El pedido está pagado en GiftHub pero el POS no registró el cobro ("
                    + cause.code() + (cause.detail() == null ? "" : ": " + cause.detail()) + "): cobrar o anular la venta a mano");
        }
        return false;
    }

    /**
     * No sale in the POS. CREATE_ORDER FAILED with its compensation confirmed (the order cancelled in GiftHub): nothing
     * to collect. FAILED without it: the money was taken for an order the POS never got, a human settles it. Anything
     * else (not here yet, running, DEAD until a replay) may still produce it: retry.
     */
    private StepResult saleMissing(StepContext context, Operation created) {
        if (created != null && created.status() == OperationStatus.FAILED) {
            String cause = created.lastError() == null ? "sin error" : created.lastError().code();
            if (created.result() != null && Boolean.TRUE.equals(created.result().get("compensated"))) {
                return skipped(context, "no-sale", "CREATE_ORDER terminó FAILED (" + cause + ") y el pedido se canceló en GiftHub");
            }
            return rejected("sale-rejected", "CREATE_ORDER terminó FAILED (" + cause + ") sin venta en el POS ni cancelación en GiftHub");
        }
        return new StepResult.Failed(ConnectorError.of(ErrorKind.TRANSIENT, "sale-pending",
                created == null ? "Todavía no llegó order.created" : "CREATE_ORDER sigue " + created.status() + " sin venta"));
    }

    /** The customer CREATE_ORDER put on the sale, null while it has not resolved one. */
    private String saleCustomerId(StepContext context, String orderId) {
        return operations.findByKey(context.operation().tenantId(), OperationKind.CREATE_ORDER, orderId)
                .map(Operation::result).map(result -> (String) result.get("customerId")).orElse(null);
    }

    /** The invoice data is not a valid RUC: the payment goes on without it and the invoice stays consumidor final. */
    private static StepResult invoiceWithoutRuc(StepContext context, String detail) {
        warn(context, "factura sin RUC válido (" + detail + "): se emite a consumidor final");
        return new StepResult.Next(PAY_POS, null, null, Map.of(INVOICE_REFUSED, true));
    }

    /** This order's invoice data for its sale; null when none was requested, it is malformed or the POS refused its RUC. */
    private static InvoiceData saleInvoice(Operation op) {
        if (op.result() != null && Boolean.TRUE.equals(op.result().get(INVOICE_REFUSED))) {
            return null;
        }
        try {
            return InvoiceData.from(op.payload().get("invoice"));
        } catch (DomainException e) {
            return null;
        }
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

    private static void warn(StepContext context, String message) {
        Operation op = context.operation();
        log.log(WARNING, "[NEXUS-ORDER] op={0} tenant={1} step={2} {3}", op.id(), op.tenantId(), op.currentStep(), message);
    }

    private static void logStep(StepContext context, String message) {
        Operation op = context.operation();
        log.log(INFO, "[NEXUS-ORDER] op={0} tenant={1} step={2} {3}", op.id(), op.tenantId(), op.currentStep(), message);
    }
}
