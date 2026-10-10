package com.mindtechpy.nexus.application.saga.order;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.EntityMappingRepository;
import com.mindtechpy.nexus.application.port.out.GiftHubPort;
import com.mindtechpy.nexus.application.port.out.PosPort;
import com.mindtechpy.nexus.application.saga.ManualReconciliationRequired;
import com.mindtechpy.nexus.application.saga.Saga;
import com.mindtechpy.nexus.application.saga.StepContext;
import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.mapping.EntityType;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.order.ChannelOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * {@code [NEXUS-ORDER]} GiftHub order → POS channel sale (spec §10.1). Steps:
 * {@code RESOLVE_CUSTOMER} (phone → POS customer, or the connection's {@code defaultCustomerId}) →
 * {@code CREATE_ORDER} (POS sale {@code PENDING} with items and toppings as product lines, GiftHub's delivery fee
 * and order discount (coupon), {@code Idempotency-Key = GIFTHUB:{orderId}}; after an uncertain attempt it first looks the sale up and adopts
 * it; a POS total other than GiftHub's {@code total_pyg} is logged and kept as {@code totalMismatch}, never
 * blocks) → {@code NOTIFY_GIFTHUB} ({@code CONFIRMED} with the sale id and number). Compensation: a POS
 * rejection (stock, unknown product) cancels the order in GiftHub with the reason, unless it is already paid
 * there (manual reconciliation); an order cancelled in GiftHub before the confirmation cancels the pending POS
 * sale. A NOTIFY that exhausts its attempts ends DEAD without compensation: the sale exists and a replay only
 * re-runs NOTIFY.
 */
public class CreateOrderSaga implements Saga {

    public static final String CHANNEL = "GIFTHUB";
    static final String RESOLVE_CUSTOMER = "RESOLVE_CUSTOMER";
    static final String CREATE_ORDER = "CREATE_ORDER";
    static final String NOTIFY_GIFTHUB = "NOTIFY_GIFTHUB";

    private static final System.Logger log = System.getLogger(CreateOrderSaga.class.getName());
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Set<String> CANCELS_IN_GIFTHUB = Set.of("insufficient-stock", "unknown-product");
    private static final Set<String> POS_PAYMENT_METHODS = Set.of("CASH", "CARD", "TRANSFER", "OTHER");
    private static final Set<String> CONFIRMED_OR_LATER = Set.of("CONFIRMED", "PREPARING", "READY", "OUT_FOR_DELIVERY", "DELIVERED");
    private static final String ORDER_CANCELLED_IN_GIFTHUB = "gifthub-order-cancelled";

    private final PosPort pos;
    private final GiftHubPort giftHub;
    private final EntityMappingRepository mappings;

    public CreateOrderSaga(PosPort pos, GiftHubPort giftHub, EntityMappingRepository mappings) {
        this.pos = pos;
        this.giftHub = giftHub;
        this.mappings = mappings;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.CREATE_ORDER;
    }

    @Override
    public String firstStep() {
        return RESOLVE_CUSTOMER;
    }

    @Override
    public StepResult execute(StepContext context) {
        try {
            OrderEndpoints endpoints = OrderEndpoints.of(context);
            ChannelOrder order = ChannelOrder.from(context.operation().payload());
            return switch (context.step()) {
                case RESOLVE_CUSTOMER -> resolveCustomer(context, endpoints, order);
                case CREATE_ORDER -> createOrder(context, endpoints, order);
                case NOTIFY_GIFTHUB -> notifyGiftHub(context, endpoints, order);
                default -> throw new IllegalStateException("Paso desconocido: " + context.step());
            };
        } catch (ConnectorException e) {
            return new StepResult.Failed(e.error());
        }
    }

    private StepResult resolveCustomer(StepContext context, OrderEndpoints endpoints, ChannelOrder order) {
        String phone = order.customer() == null ? null : order.customer().phone();
        if (phone == null || !E164.matcher(phone).matches()) {
            Object fallback = endpoints.pos().connection().settings().get("defaultCustomerId");
            if (fallback == null || fallback.toString().isBlank()) {
                return rejected("customer-missing", "El pedido no trae un teléfono E.164 y la conexión POS no tiene defaultCustomerId");
            }
            logStep(context, "cliente por defecto de la conexión");
            return new StepResult.Next(CREATE_ORDER, null, "default", merge(context, Map.of("customerId", fallback.toString())));
        }
        String name = order.customer().name() == null ? "Cliente GiftHub" : truncate(order.customer().name(), 100);
        String customerId = pos.resolveCustomer(endpoints.pos(), endpoints.readTimeout(), phone, name);
        mappings.saveAll(context.operation().tenantId(), endpoints.pos().connection().id(), EntityType.CUSTOMER,
                Map.of(phone, customerId));
        logStep(context, "cliente POS " + customerId);
        return new StepResult.Next(CREATE_ORDER, null, customerId, merge(context, Map.of("customerId", customerId)));
    }

    private StepResult createOrder(StepContext context, OrderEndpoints endpoints, ChannelOrder order) {
        PosPort.Order sale = null;
        if (context.operation().needsVerification()) {
            sale = pos.findOrder(endpoints.pos(), endpoints.readTimeout(), CHANNEL, order.orderId()).orElse(null);
            logStep(context, sale != null ? "verificación: la venta ya existe en el POS, se adopta"
                    : "verificación: la venta no existe en el POS, se reenvía con la misma clave");
        }
        if (sale == null) {
            if (!order.itemsWithoutCode().isEmpty()) {
                return rejected("unknown-product", "Productos sin código del POS: " + String.join(", ", order.itemsWithoutCode()));
            }
            sale = pos.createOrder(endpoints.pos(), endpoints.readTimeout(), CHANNEL + ":" + order.orderId(),
                    newOrder(context, endpoints, order));
        }
        if (sale.saleId() == null || sale.saleNumber() == null) {
            return new StepResult.Failed(ConnectorError.of(ErrorKind.UNCERTAIN, "pos-invalid-response",
                    "El POS respondió sin id o número de venta"));
        }
        mappings.saveAll(context.operation().tenantId(), endpoints.pos().connection().id(), EntityType.ORDER,
                Map.of(order.orderId(), sale.saleId()));
        logStep(context, "venta POS " + sale.saleNumber() + " total=" + sale.total());
        Map<String, Object> result = new HashMap<>(Map.of("saleId", sale.saleId(), "saleNumber", sale.saleNumber(), "total", sale.total()));
        if (order.totalPyg() != null) {
            boolean mismatch = sale.total() != order.totalPyg();
            result.put("giftHubTotal", order.totalPyg());
            result.put("totalMismatch", mismatch);
            if (mismatch) {
                warnTotalMismatch(context, order, sale);
            }
        }
        return new StepResult.Next(NOTIFY_GIFTHUB, null, sale.saleId(), merge(context, result));
    }

    /**
     * The POS prices the items itself, so its total can differ from what GiftHub charged: another price, a POS
     * promotion, a delivery fee without phone. It does not block the order: the cashier settles it.
     */
    private static void warnTotalMismatch(StepContext context, ChannelOrder order, PosPort.Order sale) {
        Operation op = context.operation();
        log.log(WARNING, "[NEXUS-ORDER] op={0} tenant={1} step={2} total POS {3} distinto del total GiftHub {4} "
                        + "(envío {5}, descuento {6}): se cobra en caja",
                op.id(), op.tenantId(), op.currentStep(), String.valueOf(sale.total()), String.valueOf(order.totalPyg()),
                String.valueOf(order.deliveryFeePyg()), String.valueOf(order.discountPyg()));
    }

    private StepResult notifyGiftHub(StepContext context, OrderEndpoints endpoints, ChannelOrder order) {
        Operation op = context.operation();
        String saleId = (String) op.result().get("saleId");
        String saleNumber = (String) op.result().get("saleNumber");
        try {
            giftHub.setStatus(endpoints.giftHub(), endpoints.readTimeout(), "nexus:" + op.id() + ":CONFIRMED", order.orderId(),
                    new GiftHubPort.StatusChange("CONFIRMED", "Venta POS " + saleNumber, saleId, saleNumber));
        } catch (ConnectorException e) {
            if (!"TRANSITION_NOT_ALLOWED".equals(e.error().code())) {
                throw e;
            }
            String status = giftHub.findOrder(endpoints.giftHub(), endpoints.readTimeout(), order.orderId())
                    .map(GiftHubPort.Order::status).orElse("NOT_FOUND");
            if ("CANCELLED".equals(status)) {
                return rejected(ORDER_CANCELLED_IN_GIFTHUB, "El pedido se canceló en GiftHub antes de confirmarlo");
            }
            if (!CONFIRMED_OR_LATER.contains(status)) {
                throw e;
            }
            logStep(context, "GiftHub ya estaba en " + status);
        }
        logStep(context, "GiftHub CONFIRMED con venta " + saleNumber);
        return new StepResult.Completed(saleNumber, null);
    }

    /**
     * Undoes what the failure left behind. POS rejected the order (stock, unknown product): cancel it in
     * GiftHub with the reason, or ask for manual reconciliation when it is already paid there. Order cancelled
     * in GiftHub before NOTIFY: cancel the POS sale, or ask for reconciliation when it is no longer pending.
     * Anything else needs no undo: the operation ends FAILED/DEAD for a human (replay resumes the step).
     */
    @Override
    public void compensate(StepContext context, ConnectorError cause) {
        if (CREATE_ORDER.equals(context.step()) && CANCELS_IN_GIFTHUB.contains(cause.code())) {
            cancelInGiftHub(context, cause);
        } else if (NOTIFY_GIFTHUB.equals(context.step()) && ORDER_CANCELLED_IN_GIFTHUB.equals(cause.code())) {
            cancelInPos(context);
        } else {
            logStep(context, "sin compensación para " + cause.kind() + ":" + cause.code());
        }
    }

    private void cancelInGiftHub(StepContext context, ConnectorError cause) {
        OrderEndpoints endpoints = OrderEndpoints.of(context);
        String orderId = ChannelOrder.from(context.operation().payload()).orderId();
        GiftHubPort.Order order = giftHub.findOrder(endpoints.giftHub(), endpoints.readTimeout(), orderId)
                .orElseThrow(() -> new ManualReconciliationRequired("El POS rechazó el pedido (" + cause.code()
                        + ") y el pedido no existe en GiftHub"));
        if ("CANCELLED".equals(order.status())) {
            logStep(context, "compensación: el pedido ya estaba cancelado en GiftHub");
            return;
        }
        if ("PAID".equals(order.paymentStatus())) {
            throw new ManualReconciliationRequired("El POS rechazó el pedido (" + cause.code() + ": " + cause.detail()
                    + ") pero está pagado en GiftHub: reembolsar en el panel de GiftHub y cancelarlo, o cargar la venta a mano");
        }
        giftHub.setStatus(endpoints.giftHub(), endpoints.readTimeout(), "nexus:" + context.operation().id() + ":CANCELLED",
                orderId, new GiftHubPort.StatusChange("CANCELLED", truncate("No se pudo tomar el pedido: " + cause.detail(), 500),
                        null, null));
        logStep(context, "compensación: GiftHub CANCELLED por " + cause.code());
    }

    private void cancelInPos(StepContext context) {
        OrderEndpoints endpoints = OrderEndpoints.of(context);
        String orderId = ChannelOrder.from(context.operation().payload()).orderId();
        try {
            pos.cancelOrder(endpoints.pos(), endpoints.readTimeout(), CHANNEL, orderId, "Pedido cancelado en GiftHub");
        } catch (ConnectorException e) {
            if (!"pos-order-not-pending".equals(e.error().code())) {
                throw e;
            }
            throw new ManualReconciliationRequired("El pedido se canceló en GiftHub pero la venta del POS ya no está pendiente: revisar el cobro");
        }
        logStep(context, "compensación: venta del POS anulada");
    }

    private static PosPort.NewOrder newOrder(StepContext context, OrderEndpoints endpoints, ChannelOrder order) {
        Map<String, Object> settings = endpoints.pos().connection().settings();
        List<PosPort.Item> items = order.saleLines().stream().map(line -> new PosPort.Item(line.externalId(), line.quantity())).toList();
        PosPort.Delivery delivery = order.delivery() == null || order.delivery().address() == null ? null
                : new PosPort.Delivery(truncate(order.delivery().address(), 500),
                        order.customer() == null ? null : truncate(order.customer().name(), 150),
                        order.customer() == null ? null : truncate(order.customer().phone(), 50),
                        truncate(order.delivery().reference(), 500));
        String paymentMethod = order.paymentMethod() != null && POS_PAYMENT_METHODS.contains(order.paymentMethod()) ? order.paymentMethod() : null;
        // The POS only takes a shipping cost with the delivery address and phone; without them the fee stays in the notes.
        Long fee = order.deliveryFeePyg();
        Long shippingCost = fee != null && fee > 0 && delivery != null && delivery.contactPhone() != null ? fee : null;
        Long discount = order.discountPyg() != null && order.discountPyg() > 0 ? order.discountPyg() : null;
        return new PosPort.NewOrder(CHANNEL, order.orderId(), (String) context.operation().result().get("customerId"),
                setting(settings, "branchId"), setting(settings, "warehouseId"), items, delivery, shippingCost, discount,
                paymentMethod, notes(order));
    }

    /** GiftHub's amounts for the cashier, also the ones the POS already took (fee, discount), to check its total. */
    private static String notes(ChannelOrder order) {
        List<String> lines = new ArrayList<>();
        lines.add("Pedido GiftHub " + (order.number() == null ? order.orderId() : order.number()));
        if (order.deliveryFeePyg() != null && order.deliveryFeePyg() > 0) {
            lines.add("Envío GiftHub: Gs. " + order.deliveryFeePyg());
        }
        if (order.discountPyg() != null && order.discountPyg() > 0) {
            lines.add("Descuento GiftHub: Gs. " + order.discountPyg());
        }
        if (order.totalPyg() != null) {
            lines.add("Total GiftHub: Gs. " + order.totalPyg());
        }
        if (order.notes() != null) {
            lines.add(order.notes());
        }
        order.items().stream().filter(item -> item.options() != null)
                .forEach(item -> lines.add(item.name() + ": " + item.options()));
        return truncate(String.join("\n", lines), 1000);
    }

    private static String setting(Map<String, Object> settings, String key) {
        Object value = settings.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Map<String, Object> merge(StepContext context, Map<String, Object> values) {
        Map<String, Object> result = new HashMap<>(context.operation().result() == null ? Map.of() : context.operation().result());
        result.putAll(values);
        return result;
    }

    private static StepResult rejected(String code, String detail) {
        return new StepResult.Failed(ConnectorError.of(ErrorKind.REJECTED, code, detail));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static void logStep(StepContext context, String message) {
        Operation op = context.operation();
        log.log(INFO, "[NEXUS-ORDER] op={0} tenant={1} step={2} {3}", op.id(), op.tenantId(), op.currentStep(), message);
    }
}
