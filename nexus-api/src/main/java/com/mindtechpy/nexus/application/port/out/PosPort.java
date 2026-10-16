package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;
import com.mindtechpy.nexus.domain.order.InvoiceData;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * punto-venta integration API ({@code /api/integration/*}, {@code X-Api-Key} per tenant). Every method
 * throws {@link ConnectorException} with the failure already classified.
 */
public interface PosPort {

    /** {@code unitPrice}: whole guaraníes per unit, IVA included, used instead of the POS catalog price (null = catalog). */
    record Item(String code, BigDecimal quantity, Long unitPrice) {
    }

    record Delivery(String address, String contactName, String contactPhone, String instructions) {
    }

    /**
     * {@code branchId}/{@code warehouseId} null: the POS uses the defaults of the API key. {@code shippingCost}
     * (whole guaraníes, null = none) is the channel's delivery fee, with or without a delivery address; the POS adds it to its total. {@code discount}
     * (whole guaraníes, null = none) is the channel's order discount (coupon); the POS takes it off the items.
     */
    record NewOrder(String channel, String externalRef, String customerId, String branchId, String warehouseId,
                    List<Item> items, Delivery delivery, Long shippingCost, Long discount, String paymentMethod,
                    String notes) {
    }

    record Order(String saleId, String saleNumber, long total, String status) {
    }

    /**
     * A payment to register on a channel sale: {@code amount} in whole guaraníes, {@code reference} optional (≤ 100).
     * {@code invoice} (null = none): the POS freezes it on the sale in the same transaction and invoices to that RUC.
     */
    record Payment(String method, long amount, String reference, InvoiceData invoice) {
    }

    /** The sale after the POS took the payment; {@code status} is {@code COMPLETED} when it really closed. */
    record PaidSale(String saleId, String saleNumber, String status, boolean invoiceQueued) {
    }

    /**
     * {@code priceLabel}: how the POS wants the price shown (null = none); it is forwarded to GiftHub as is.
     * {@code description}: the POS product description (null = none).
     */
    record CatalogItem(String code, String name, String description, BigDecimal price, BigDecimal stockQty, boolean tracked,
                       String category, String imageUrl, String priceLabel) {
    }

    record CatalogPage(List<CatalogItem> items, int page, int limit, long total) {
    }

    /**
     * Customer id for the phone (E.164), created with {@code name} when it does not exist. With {@code invoice}
     * (null = none) the POS also sets its RUC, DV and business name (idempotent); a RUC whose DV does not match
     * fails {@code REJECTED invalid-ruc}, one another customer already has fails {@code REJECTED ruc-in-use}.
     */
    String resolveCustomer(Endpoint pos, Duration readTimeout, String phoneE164, String name, InvoiceData invoice);

    /** Creates the channel sale; {@code idempotencyKey} must be {@code {channel}:{externalRef}}. */
    Order createOrder(Endpoint pos, Duration readTimeout, String idempotencyKey, NewOrder order);

    Optional<Order> findOrder(Endpoint pos, Duration readTimeout, String channel, String externalRef);

    /**
     * Registers the payment of a pending channel sale and closes it; {@code idempotencyKey} must be
     * {@code {channel}:{externalRef}:paid} (repeating it answers the same closed sale). 404 fails
     * {@code REJECTED pos-not-found}; 409 fails {@code REJECTED} with the POS code ({@code not-pending},
     * {@code sale-cancelled}, {@code amount-mismatch}) except {@code cash-session-closed}, which is
     * {@code TRANSIENT}; 422 fails {@code REJECTED insufficient-stock}.
     */
    PaidSale payChannelOrder(Endpoint pos, Duration readTimeout, String idempotencyKey, String channel, String externalRef,
                             Payment payment);

    /**
     * Cancels a channel sale that is still pending (repeating it answers the cancelled sale). Empty when the
     * POS has no such sale; a sale that is no longer pending fails {@code REJECTED pos-order-not-pending}.
     */
    Optional<Order> cancelOrder(Endpoint pos, Duration readTimeout, String channel, String externalRef, String reason);

    /** One page of the sellable catalog (active and sellable products, stock of the key's warehouse). */
    CatalogPage fetchCatalog(Endpoint pos, Duration readTimeout, int page, int limit);
}
