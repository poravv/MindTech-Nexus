package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GiftHub integrations API v1 ({@code Authorization: Bearer gh_live_…}). Order writes carry a stable
 * {@code Idempotency-Key}; asking for the status the order already has, or paying a paid order, is a no-op
 * on GiftHub's side. Every method throws {@link ConnectorException} with the failure already classified.
 */
public interface GiftHubPort {

    /** {@code status}: PENDING…DELIVERED/CANCELLED; {@code paymentStatus}: PENDING, PAID, REJECTED, REFUNDED. */
    record Order(String id, String status, String paymentStatus, String fulfillment) {
    }

    /** {@code externalRef}/{@code externalNumber}: the POS sale, shown in the GiftHub panel. */
    record StatusChange(String status, String note, String externalRef, String externalNumber) {
    }

    /** {@code stockQty} null: GiftHub does not track stock for the product. */
    record Product(String externalId, String name, String category, long pricePyg, boolean available, Long stockQty,
                   String imageUrl) {
    }

    record RowError(String externalId, String error) {
    }

    /** {@code productIds}: GiftHub product id by external id, for the rows that were applied. */
    record BulkResult(int created, int updated, int stale, List<RowError> errors, Map<String, String> productIds) {
    }

    Optional<Order> findOrder(Endpoint giftHub, Duration readTimeout, String orderId);

    Order setStatus(Endpoint giftHub, Duration readTimeout, String idempotencyKey, String orderId, StatusChange change);

    /** {@code method}: CASH, TRANSFER, CARD or OTHER. */
    Order markPaid(Endpoint giftHub, Duration readTimeout, String idempotencyKey, String orderId, String reference,
                   String method);

    /** Upserts up to 500 products; {@code dryRun} only reports what would happen. */
    BulkResult upsertProducts(Endpoint giftHub, Duration readTimeout, List<Product> products, boolean dryRun);
}
