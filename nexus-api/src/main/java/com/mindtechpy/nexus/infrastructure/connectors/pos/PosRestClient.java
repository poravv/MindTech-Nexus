package com.mindtechpy.nexus.infrastructure.connectors.pos;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.PosPort;
import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp;
import com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp.Response;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp.object;
import static com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp.text;

/**
 * punto-venta {@code /api/integration/*} with the tenant's {@code X-Api-Key} (credential {@code apiKey},
 * scopes {@code catalog:read customers:write orders:write}). Bodies carry exactly the fields of the POS DTOs:
 * its validation rejects unknown ones. Statuses: 422 {@code insufficient-stock|unknown-product} → REJECTED
 * with that code; 404 (customer/branch/warehouse of another organization) → REJECTED {@code pos-not-found};
 * 409 on create (same reference, other content) → CONFLICT; 400 → REJECTED.
 */
@Component
class PosRestClient implements PosPort {

    private final ConnectorHttp http;

    PosRestClient(ConnectorHttp http) {
        this.http = http;
    }

    @Override
    public String resolveCustomer(Endpoint pos, Duration readTimeout, String phoneE164, String name) {
        return http.timed("POS", "resolve_customer", () -> {
            Response response = http.call(pos.connection().baseUrl(), readTimeout, HttpMethod.POST,
                    "/api/integration/customers/resolve", apiKey(pos), object("phoneE164", phoneE164, "name", name));
            String customerId = text(requireSuccess(response, false), "customerId");
            if (customerId == null) {
                throw new ConnectorException(ConnectorHttp.error(ErrorKind.UNCERTAIN, "pos-invalid-response",
                        "El POS no devolvió customerId", response));
            }
            return customerId;
        });
    }

    @Override
    public Order createOrder(Endpoint pos, Duration readTimeout, String idempotencyKey, NewOrder order) {
        return http.timed("POS", "create_order", () -> {
            List<Object> items = new ArrayList<>();
            order.items().forEach(item -> items.add(object("code", item.code(), "quantity", item.quantity())));
            Object delivery = order.delivery() == null ? null : object("address", order.delivery().address(),
                    "contactName", order.delivery().contactName(), "contactPhone", order.delivery().contactPhone(),
                    "instructions", order.delivery().instructions());
            Object body = object("channel", order.channel(), "externalRef", order.externalRef(), "customerId", order.customerId(),
                    "branchId", order.branchId(), "warehouseId", order.warehouseId(), "items", items, "delivery", delivery,
                    "shippingCost", order.shippingCost(), "discountAmount", order.discount(),
                    "paymentMethod", order.paymentMethod(), "notes", order.notes());
            Response response = http.call(pos.connection().baseUrl(), readTimeout, HttpMethod.POST, "/api/integration/orders",
                    apiKey(pos).andThen(headers -> headers.set("Idempotency-Key", idempotencyKey)), body);
            return order(requireSuccess(response, true));
        });
    }

    @Override
    public Optional<Order> findOrder(Endpoint pos, Duration readTimeout, String channel, String externalRef) {
        return http.timed("POS", "find_order", () -> {
            Response response = http.call(pos.connection().baseUrl(), readTimeout, HttpMethod.GET, orderPath(channel, externalRef),
                    apiKey(pos), null);
            return response.status() == 404 ? Optional.empty() : Optional.of(order(requireSuccess(response, false)));
        });
    }

    @Override
    public Optional<Order> cancelOrder(Endpoint pos, Duration readTimeout, String channel, String externalRef, String reason) {
        return http.timed("POS", "cancel_order", () -> {
            Response response = http.call(pos.connection().baseUrl(), readTimeout, HttpMethod.POST,
                    orderPath(channel, externalRef) + "/cancel", apiKey(pos), object("reason", reason));
            if (response.status() == 404) {
                return Optional.empty();
            }
            if (response.status() == 409) {
                throw new ConnectorException(ConnectorHttp.error(ErrorKind.REJECTED, "pos-order-not-pending",
                        text(response.body(), "message"), response));
            }
            return Optional.of(order(requireSuccess(response, false)));
        });
    }

    @Override
    public CatalogPage fetchCatalog(Endpoint pos, Duration readTimeout, int page, int limit) {
        return http.timed("POS", "fetch_catalog", () -> {
            Response response = http.call(pos.connection().baseUrl(), readTimeout, HttpMethod.GET,
                    "/api/integration/catalog?page=" + page + "&limit=" + limit, apiKey(pos), null);
            JsonNode body = requireSuccess(response, false);
            if (!body.path("items").isArray() || !body.path("total").isNumber()) {
                throw new ConnectorException(ConnectorHttp.error(ErrorKind.UNCERTAIN, "pos-invalid-response",
                        "El catálogo del POS no trae items/total", response));
            }
            List<CatalogItem> items = new ArrayList<>();
            for (JsonNode item : body.path("items")) {
                items.add(new CatalogItem(text(item, "code"), text(item, "name"), item.path("price").decimalValue(),
                        item.path("stockQty").decimalValue(), item.path("tracked").asBoolean(true), text(item, "category"),
                        text(item, "imageUrl")));
            }
            return new CatalogPage(items, body.path("page").asInt(page), body.path("limit").asInt(limit), body.path("total").asLong());
        });
    }

    private static JsonNode requireSuccess(Response response, boolean creatingOrder) {
        if (response.isSuccess()) {
            return response.body();
        }
        String message = text(response.body(), "message");
        ConnectorError common = ConnectorHttp.commonError(response, "pos-http-" + response.status(), message);
        if (common != null) {
            throw new ConnectorException(common);
        }
        String code = text(response.body(), "code");
        ConnectorError error = switch (response.status()) {
            case 422 -> ConnectorHttp.error(ErrorKind.REJECTED, code == null ? "pos-unprocessable" : code, message, response);
            case 404 -> ConnectorHttp.error(ErrorKind.REJECTED, "pos-not-found", message, response);
            case 409 -> ConnectorHttp.error(creatingOrder ? ErrorKind.CONFLICT : ErrorKind.REJECTED, "pos-conflict", message, response);
            default -> ConnectorHttp.error(ErrorKind.REJECTED, "pos-bad-request", message, response);
        };
        throw new ConnectorException(error);
    }

    private static Order order(JsonNode body) {
        BigDecimal total = body.path("total").isNumber() ? body.path("total").decimalValue() : BigDecimal.ZERO;
        return new Order(text(body, "saleId"), text(body, "saleNumber"), total.longValue(), text(body, "status"));
    }

    /** Both values match {@code [A-Za-z0-9:_-]} (validated with the order), so they need no encoding. */
    private static String orderPath(String channel, String externalRef) {
        return "/api/integration/orders/" + channel + "/" + externalRef;
    }

    private static Consumer<HttpHeaders> apiKey(Endpoint pos) {
        String key = ConnectorHttp.credential(pos.credentials(), "apiKey");
        return headers -> headers.set("X-Api-Key", key);
    }
}
