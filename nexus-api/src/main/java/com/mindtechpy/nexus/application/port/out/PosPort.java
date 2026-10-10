package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * punto-venta integration API ({@code /api/integration/*}, {@code X-Api-Key} per tenant). Every method
 * throws {@link ConnectorException} with the failure already classified.
 */
public interface PosPort {

    record Item(String code, BigDecimal quantity) {
    }

    record Delivery(String address, String contactName, String contactPhone, String instructions) {
    }

    /** {@code branchId}/{@code warehouseId} null: the POS uses the defaults of the API key. */
    record NewOrder(String channel, String externalRef, String customerId, String branchId, String warehouseId,
                    List<Item> items, Delivery delivery, String paymentMethod, String notes) {
    }

    record Order(String saleId, String saleNumber, long total, String status) {
    }

    record CatalogItem(String code, String name, BigDecimal price, BigDecimal stockQty, boolean tracked, String category,
                       String imageUrl) {
    }

    record CatalogPage(List<CatalogItem> items, int page, int limit, long total) {
    }

    /** Customer id for the phone (E.164), created with {@code name} when it does not exist. */
    String resolveCustomer(Endpoint pos, Duration readTimeout, String phoneE164, String name);

    /** Creates the channel sale; {@code idempotencyKey} must be {@code {channel}:{externalRef}}. */
    Order createOrder(Endpoint pos, Duration readTimeout, String idempotencyKey, NewOrder order);

    Optional<Order> findOrder(Endpoint pos, Duration readTimeout, String channel, String externalRef);

    /**
     * Cancels a channel sale that is still pending (repeating it answers the cancelled sale). Empty when the
     * POS has no such sale; a sale that is no longer pending fails {@code REJECTED pos-order-not-pending}.
     */
    Optional<Order> cancelOrder(Endpoint pos, Duration readTimeout, String channel, String externalRef, String reason);

    /** One page of the sellable catalog (active and sellable products, stock of the key's warehouse). */
    CatalogPage fetchCatalog(Endpoint pos, Duration readTimeout, int page, int limit);
}
