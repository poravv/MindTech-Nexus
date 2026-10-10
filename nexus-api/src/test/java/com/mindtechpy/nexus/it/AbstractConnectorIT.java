package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.infrastructure.connectors.gifthub.GiftHubWebhookSignature;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Order and catalog flows against WireMock fakes of the POS and GiftHub. Every test gets its own tenant and
 * its connections point to {@code http://localhost:<port>/<tenant>}, so the requests of one test never mix
 * with leftovers of another; stub and verify through {@link #pos(String)} / {@link #gh(String)}.
 */
abstract class AbstractConnectorIT extends AbstractOperationIT {

    static final WireMockServer POS = new WireMockServer(options().dynamicPort());
    static final WireMockServer GIFTHUB = new WireMockServer(options().dynamicPort());
    static final String WEBHOOK_SECRET = "whsec-test-secret";

    static {
        POS.start();
        GIFTHUB.start();
    }

    @Autowired
    private ConnectionRepository connections;
    @Autowired
    private SecretCipherPort cipher;

    protected UUID posConnection;
    protected UUID giftHubConnection;

    @BeforeEach
    void setUpConnections() {
        posConnection = insertConnection(ConnectorType.POS, POS, Map.of("apiKey", "pv_live_test"),
                Map.of("defaultCustomerId", "c-default"));
        giftHubConnection = insertConnection(ConnectorType.GIFTHUB, GIFTHUB,
                Map.of("apiKey", "gh_live_test", "webhookSecret", WEBHOOK_SECRET), Map.of());
    }

    /** Straight to the repository: the test base URL (localhost) is outside the connector allowlist. */
    protected UUID insertConnection(ConnectorType type, WireMockServer server, Map<String, String> credentials,
                                    Map<String, Object> settings) {
        UUID id = UUID.randomUUID();
        TenantId tenantId = new TenantId(tenant);
        connections.insert(new Connection(id, tenantId, type, type.name().toLowerCase() + "-" + id, server.baseUrl() + "/" + tenant,
                cipher.seal(new ConnectionCredentials(credentials), Connection.credentialsAad(tenantId, id)), settings, null, null));
        return id;
    }

    /** Fast retries and a 1 s read timeout, so timeouts and exhaustion are quick to provoke. */
    protected static Map<String, Object> connectorPolicy() {
        Map<String, Object> policy = fastPolicy();
        policy.put("readTimeoutMs", 1000);
        return policy;
    }

    protected UUID createIntegration(String flow, UUID source, UUID target) {
        Map<String, Object> request = new HashMap<>();
        request.put("flow", flow);
        request.put("code", flow.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8));
        request.put("sourceConnectionId", source.toString());
        request.put("targetConnectionId", target.toString());
        request.put("policy", connectorPolicy());
        return UUID.fromString(body(perform(post("/api/admin/integrations").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request)), 201)).get("id").asString());
    }

    /** With the CATALOG integration it requires (POS → GiftHub). */
    protected UUID orderIntegration() {
        createIntegration("CATALOG", posConnection, giftHubConnection);
        return createIntegration("ORDER", giftHubConnection, posConnection);
    }

    protected String pos(String path) {
        return "/" + tenant + path;
    }

    protected String gh(String path) {
        return "/" + tenant + "/api/integrations/v1" + path;
    }

    protected void stubPos(MappingBuilder stub) {
        POS.stubFor(stub);
    }

    protected void stubGiftHub(MappingBuilder stub) {
        GIFTHUB.stubFor(stub);
    }

    protected List<LoggedRequest> posRequests(String method, String path) {
        return POS.findAll(WireMock.requestedFor(method, WireMock.urlPathEqualTo(pos(path))));
    }

    protected List<LoggedRequest> giftHubRequests(String method, String path) {
        return GIFTHUB.findAll(WireMock.requestedFor(method, WireMock.urlPathEqualTo(gh(path))));
    }

    protected JsonNode jsonOf(LoggedRequest request) {
        return json.readTree(request.getBodyAsString());
    }

    /** GiftHub order as {@code GET /orders/{id}} and the status/paid writes answer it (manual §5.1). */
    protected static String giftHubOrder(String orderId, String status, String paymentStatus, String fulfillment) {
        return """
                {"id":"%s","number":"BH-1","status":"%s","payment_status":"%s","fulfillment":"%s","total_pyg":91000,"items":[]}"""
                .formatted(orderId, status, paymentStatus, fulfillment);
    }

    /** {@code order.created} as GiftHub sends it (manual §7.3); {@code customer} null when the owner hides it. */
    protected String orderCreated(String deliveryId, String orderId, Map<String, Object> customer) {
        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", "BH-7Q2K");
        order.put("status", "PENDING");
        order.put("payment_status", "PENDING");
        order.put("payment_method", "TRANSFER");
        order.put("fulfillment", "DELIVERY");
        order.put("notes", "Sin cebolla");
        order.put("delivery", Map.of("address", "Av. España 1234", "reference", "Portón negro"));
        order.put("subtotal_pyg", 81000);
        order.put("discount_pyg", 0);
        order.put("delivery_fee_pyg", 10000);
        order.put("total_pyg", 91000);
        order.put("items", List.of(Map.of("external_id", "SKU-1001", "name", "Hamburguesa doble", "quantity", 2,
                "unit_price_pyg", 38000, "line_total_pyg", 76000,
                "options", List.of(Map.of("group", "Punto", "values", List.of("A punto"))),
                "toppings", List.of(Map.of("external_id", "SKU-2001", "name", "Extra cheddar", "quantity", 2,
                        "unit_price_pyg", 2500, "line_total_pyg", 5000)))));
        order.put("customer", customer);
        order.put("created_at", "2026-10-08T14:02:58.000Z");
        return toJson(Map.of("id", deliveryId, "type", "order.created",
                "created_at", "2026-10-08T14:03:00.000Z", "data", Map.of("order", order)));
    }

    protected static Map<String, Object> maria() {
        return Map.of("id", "cust-1", "name", "María López", "phone", "+595981123456");
    }

    protected MvcResult postWebhook(UUID connectionId, String deliveryId, String body, String signature, String timestamp,
                                    int expected) {
        return perform(post("/api/v1/webhooks/gifthub/{id}", connectionId).contentType(MediaType.APPLICATION_JSON)
                .header("X-GiftHub-Event", "order.created")
                .header("X-GiftHub-Delivery", deliveryId)
                .header("X-GiftHub-Timestamp", timestamp)
                .header("X-GiftHub-Signature", signature)
                .content(body), expected);
    }

    protected JsonNode sendSigned(String deliveryId, String body, int expected) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return body(postWebhook(giftHubConnection, deliveryId, body,
                GiftHubWebhookSignature.sign(WEBHOOK_SECRET, timestamp, body.getBytes(StandardCharsets.UTF_8)), timestamp, expected));
    }
}
