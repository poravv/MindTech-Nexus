package com.mindtechpy.nexus.infrastructure.connectors.gifthub;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.GiftHubPort;
import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp;
import com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp.Response;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp.object;
import static com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp.text;

/**
 * GiftHub integrations API v1 ({@code /api/integrations/v1}, {@code Authorization: Bearer} with the
 * credential {@code apiKey}). Errors come as {@code {error, code}}: 409 {@code TRANSITION_NOT_ALLOWED} →
 * REJECTED with that code; 409 {@code IDEMPOTENCY_CONFLICT} (the first call with the key is still running or
 * was cut) → UNCERTAIN, so the saga re-reads before resending; 400/404/413/415/422 → REJECTED with GiftHub's
 * code; 403 (integrations disabled, IP not allowed) → AUTH.
 */
@Component
class GiftHubRestClient implements GiftHubPort {

    private static final String BASE = "/api/integrations/v1";

    private final ConnectorHttp http;

    GiftHubRestClient(ConnectorHttp http) {
        this.http = http;
    }

    @Override
    public Optional<Order> findOrder(Endpoint giftHub, Duration readTimeout, String orderId) {
        return http.timed("GIFTHUB", "find_order", () -> {
            Response response = http.call(giftHub.connection().baseUrl(), readTimeout, HttpMethod.GET, BASE + "/orders/" + orderId,
                    bearer(giftHub, null), null);
            return response.status() == 404 ? Optional.empty() : Optional.of(order(requireSuccess(response)));
        });
    }

    @Override
    public Order setStatus(Endpoint giftHub, Duration readTimeout, String idempotencyKey, String orderId, StatusChange change) {
        return http.timed("GIFTHUB", "set_status", () -> {
            Response response = http.call(giftHub.connection().baseUrl(), readTimeout, HttpMethod.POST,
                    BASE + "/orders/" + orderId + "/status", bearer(giftHub, idempotencyKey),
                    object("status", change.status(), "note", change.note(), "external_ref", change.externalRef(),
                            "external_number", change.externalNumber()));
            return order(requireSuccess(response));
        });
    }

    @Override
    public Order markPaid(Endpoint giftHub, Duration readTimeout, String idempotencyKey, String orderId, String reference,
                          String method) {
        return http.timed("GIFTHUB", "mark_paid", () -> {
            Response response = http.call(giftHub.connection().baseUrl(), readTimeout, HttpMethod.POST,
                    BASE + "/orders/" + orderId + "/paid", bearer(giftHub, idempotencyKey),
                    object("reference", reference, "method", method));
            return order(requireSuccess(response));
        });
    }

    /**
     * {@code stock_qty}, {@code price_label} and {@code description} always travel ({@code null} turns stock control off /
     * clears the label or the description, so the POS stays authoritative); {@code image_url} only when set.
     */
    @Override
    public BulkResult upsertProducts(Endpoint giftHub, Duration readTimeout, List<Product> products, boolean dryRun) {
        return http.timed("GIFTHUB", "upsert_products", () -> {
            List<Object> rows = new ArrayList<>();
            for (Product product : products) {
                Map<String, Object> row = new LinkedHashMap<>(object("external_id", product.externalId(), "name", product.name(),
                        "category", product.category(), "price_pyg", product.pricePyg(), "available", product.available(),
                        "image_url", product.imageUrl()));
                row.put("stock_qty", product.stockQty());
                row.put("price_label", product.priceLabel());
                row.put("description", product.description());
                rows.add(row);
            }
            Response response = http.call(giftHub.connection().baseUrl(), readTimeout, HttpMethod.PUT,
                    BASE + "/products/bulk" + (dryRun ? "?dry_run=1" : ""), bearer(giftHub, null), Map.of("products", rows));
            JsonNode body = requireSuccess(response);
            return dryRun ? dryRunResult(body) : bulkResult(body);
        });
    }

    @Override
    public BulkResult archiveProducts(Endpoint giftHub, Duration readTimeout, List<String> externalIds) {
        return http.timed("GIFTHUB", "archive_products", () -> {
            List<Object> rows = externalIds.stream().<Object>map(id -> object("external_id", id, "archived", true)).toList();
            Response response = http.call(giftHub.connection().baseUrl(), readTimeout, HttpMethod.PUT, BASE + "/products/bulk",
                    bearer(giftHub, null), Map.of("products", rows));
            return bulkResult(requireSuccess(response));
        });
    }

    private static BulkResult bulkResult(JsonNode body) {
        int created = 0;
        int updated = 0;
        int stale = 0;
        List<RowError> errors = new ArrayList<>();
        Map<String, String> productIds = new HashMap<>();
        for (JsonNode row : body.path("results")) {
            String externalId = text(row, "external_id");
            switch (String.valueOf(text(row, "result"))) {
                case "created" -> created++;
                case "updated" -> updated++;
                case "stale" -> stale++;
                default -> errors.add(new RowError(externalId, text(row, "error")));
            }
            if (externalId != null && text(row, "product_id") != null) {
                productIds.put(externalId, text(row, "product_id"));
            }
        }
        return new BulkResult(created, updated, stale, errors, productIds);
    }

    /** Dry-run rows ({@code action}: crear, actualizar, omitir, error) with nothing written. */
    private static BulkResult dryRunResult(JsonNode body) {
        int created = 0;
        int updated = 0;
        int stale = 0;
        List<RowError> errors = new ArrayList<>();
        for (JsonNode row : body.path("rows")) {
            switch (String.valueOf(text(row, "action"))) {
                case "crear" -> created++;
                case "actualizar" -> updated++;
                case "omitir" -> stale++;
                default -> errors.add(new RowError(text(row, "externalId"), text(row, "error")));
            }
        }
        return new BulkResult(created, updated, stale, errors, Map.of());
    }

    private static JsonNode requireSuccess(Response response) {
        if (response.isSuccess()) {
            return response.body();
        }
        String code = text(response.body(), "code");
        ConnectorError common = ConnectorHttp.commonError(response, code == null ? "gifthub-http-" + response.status() : code);
        if (common != null) {
            throw new ConnectorException(common);
        }
        ErrorKind kind = "IDEMPOTENCY_CONFLICT".equals(code) ? ErrorKind.UNCERTAIN : ErrorKind.REJECTED;
        throw new ConnectorException(ConnectorHttp.error(kind, code == null ? "gifthub-http-" + response.status() : code, response));
    }

    private static Order order(JsonNode body) {
        return new Order(text(body, "id"), text(body, "status"), text(body, "payment_status"), text(body, "fulfillment"));
    }

    private static Consumer<HttpHeaders> bearer(Endpoint giftHub, String idempotencyKey) {
        String apiKey = ConnectorHttp.credential(giftHub.credentials(), "apiKey");
        return headers -> {
            headers.setBearerAuth(apiKey);
            if (idempotencyKey != null) {
                headers.set("Idempotency-Key", idempotencyKey);
            }
        };
    }
}
