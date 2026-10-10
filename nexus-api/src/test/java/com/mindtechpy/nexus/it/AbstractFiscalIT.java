package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fiscal flow against a WireMock SIFEN: each test gets a tenant with a SIFEN connection (pointed at
 * WireMock after creation, since the allowlist only admits cluster hosts), a FISCAL integration with a
 * 1 s read timeout and a POS-like client token. Every test uses its own {@code sourceRef}, which stubs
 * and request counts filter on.
 */
abstract class AbstractFiscalIT extends AbstractOperationIT {

    static final WireMockServer SIFEN = new WireMockServer(options().dynamicPort());
    static final String ACCESS_KEY = "ak_test_nexus";
    static final String SECRET_KEY = "sk_test_nexus_0123456789";
    static final String WEBHOOK_SECRET = "whsec_test_nexus";
    static final int READ_TIMEOUT_MS = 1000;

    static {
        SIFEN.start();
    }

    protected UUID sifenConnection;
    protected UUID fiscalIntegration;
    protected String pos;

    @BeforeEach
    void setUpFiscal() {
        SIFEN.resetAll();
        sifenConnection = UUID.fromString(body(perform(post("/api/admin/connections").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("connector", "SIFEN", "name", "sifen",
                        "baseUrl", "http://backend.sifen.svc.cluster.local:8081",
                        "credentials", Map.of("accessKey", ACCESS_KEY, "secretKey", SECRET_KEY,
                                "webhookSecret", WEBHOOK_SECRET)))), 201)).get("id").asString());
        jdbc.sql("UPDATE connections SET base_url = :url WHERE id = :id")
                .param("url", SIFEN.baseUrl()).param("id", sifenConnection).update();
        Map<String, Object> policy = fastPolicy();
        policy.put("readTimeoutMs", READ_TIMEOUT_MS);
        fiscalIntegration = UUID.fromString(body(perform(post("/api/admin/integrations").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("flow", "FISCAL", "code", "fiscal", "targetConnectionId", sifenConnection.toString(),
                        "policy", policy))), 201)).get("id").asString());
        pos = clientToken(tenant, "fiscal:issue", "fiscal:read", "operations:read");
    }

    protected static String newSourceRef() {
        return UUID.randomUUID().toString();
    }

    /** Canonical consumer-final invoice as the POS builds it (spec §8.1). */
    protected static Map<String, Object> invoice(String sourceRef) {
        Map<String, Object> request = new HashMap<>();
        request.put("sourceRef", sourceRef);
        request.put("documentType", "INVOICE");
        request.put("issuedAt", "2026-10-10T14:05:00-03:00");
        request.put("branchRef", "SUC-001");
        request.put("pointOfSaleRef", "CAJA-01");
        request.put("referenceCdc", null);
        request.put("issueReasonCode", null);
        request.put("header", Map.of("currency", "PYG", "paymentCondition", "CASH", "paymentMethod", "EFECTIVO"));
        Map<String, Object> customer = new HashMap<>();
        customer.put("documentType", "INNOMINADO");
        customer.put("documentNumber", "0");
        customer.put("name", "CONSUMIDOR FINAL");
        customer.put("email", null);
        request.put("customer", customer);
        request.put("items", List.of(Map.of("code", "PAN-001", "description", "Chipa", "quantity", "4",
                "unitPrice", 1100, "discount", 0, "vatRate", "10")));
        request.put("globalDiscount", 0);
        return request;
    }

    protected MvcResult issue(String token, Map<String, Object> request, int expected) {
        return perform(post("/api/v1/invoices")
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request)), expected);
    }

    /** Issues through the API and returns the persisted operation. */
    protected Operation issue(String sourceRef) {
        issue(pos, invoice(sourceRef), 202);
        return issueOperation(sourceRef);
    }

    protected Operation issueOperation(String sourceRef) {
        return operations.findByKey(new TenantId(tenant), OperationKind.ISSUE_INVOICE, sourceRef).orElseThrow();
    }

    protected JsonNode invoiceState(String sourceRef) {
        return body(perform(get("/api/v1/invoices/{ref}", sourceRef).header("Authorization", bearer(pos)), 200));
    }

    protected Instant nextRunAt(UUID operationId) {
        return jdbc.sql("SELECT next_run_at FROM operations WHERE id = :id").param("id", operationId)
                .query(OffsetDateTime.class).single().toInstant();
    }

    protected static void stubSubmit(String sourceRef, ResponseDefinitionBuilder response) {
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas"))
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(sourceRef))).willReturn(response));
    }

    protected static void stubFind(String sourceRef, ResponseDefinitionBuilder response) {
        SIFEN.stubFor(WireMock.get(urlEqualTo("/api/v1/facturas/" + sourceRef)).willReturn(response));
    }

    protected static RequestPatternBuilder submitsOf(String sourceRef) {
        return postRequestedFor(urlEqualTo("/api/v1/facturas"))
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(sourceRef)));
    }

    protected static RequestPatternBuilder findsOf(String sourceRef) {
        return getRequestedFor(urlEqualTo("/api/v1/facturas/" + sourceRef));
    }

    protected static int count(RequestPatternBuilder pattern) {
        return SIFEN.findAll(pattern).size();
    }

    protected static String cdcFor(String sourceRef) {
        return "0180098765400100100" + Math.abs(sourceRef.hashCode()) % 1_000_000_000L;
    }

    /** {@code FacturaComercialResponse} of SIFEN. */
    protected static ResponseDefinitionBuilder document(String sourceRef, String estado) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                {"transactionId":"%s","emisorId":"EMISOR-1","establecimiento":"001","puntoExpedicion":"001",
                 "numeroDocumento":"0000123","cdc":"%s","estado":"%s","codigoRespuesta":"0260",
                 "mensajeRespuesta":"Autorizacion del DE satisfactoria","total":4400.00,"moneda":"PYG",
                 "actualizadoEn":"2026-10-10T17:05:00Z","tipoDocumento":"FACTURA"}"""
                .formatted(sourceRef, cdcFor(sourceRef), estado));
    }

    /** {@code ApiErrorBody} of SIFEN. */
    protected static ResponseDefinitionBuilder sifenError(int status, String code, String message) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody("""
                {"timestamp":"2026-10-10T17:05:00Z","status":%d,"error":"x","code":"%s","message":"%s","details":[]}"""
                .formatted(status, code, message));
    }
}
