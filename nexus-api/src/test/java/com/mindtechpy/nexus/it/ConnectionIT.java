package com.mindtechpy.nexus.it;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class ConnectionIT extends AbstractPostgresIT {

    private static final String SECRET_KEY = "sk-super-secret-" + UUID.randomUUID();

    private String admin;
    private UUID tenant;

    @BeforeEach
    void setUp() {
        admin = platformAdminToken();
        tenant = createTenant(admin);
    }

    @Test
    void should_return_422_problem_when_base_url_is_outside_allowlist() {
        MvcResult result = create("http://evil.example.com", 422);
        JsonNode problem = body(result);

        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(problem.get("code").asString()).isEqualTo("invalid-base-url");
        assertThat(problem.get("type").asString()).isEqualTo("https://nexus.mindtechpy.net/problems/invalid-base-url");
        create("http://10.43.0.10:8081", 422);
        create("http://user:pw@backend.sifen.svc.cluster.local", 422);
    }

    @Test
    void should_never_expose_credentials_when_creating_reading_or_testing() {
        MvcResult created = create("http://backend.sifen.svc.cluster.local:8081", 201);
        String id = body(created).get("id").asString();

        String detail = content(perform(get("/api/admin/connections/{id}", id).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200));
        MvcResult tested = perform(post("/api/admin/connections/{id}/test", id).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200);

        assertThat(content(created)).doesNotContain(SECRET_KEY).doesNotContain("ak-live");
        assertThat(detail).doesNotContain(SECRET_KEY).contains("\"hasCredentials\":true");
        assertThat(content(tested)).doesNotContain(SECRET_KEY);
        byte[] stored = jdbc.sql("SELECT credentials_enc FROM connections WHERE id = :id")
                .param("id", UUID.fromString(id)).query(byte[].class).single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(SECRET_KEY);
    }

    @Test
    void should_record_audit_with_correlation_id_when_connection_changes() {
        String id = body(create("http://backend.sifen.svc.cluster.local:8081", 201)).get("id").asString();

        perform(put("/api/admin/connections/{id}", id).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).header("X-Correlation-Id", "corr-update-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "sifen-renamed", "baseUrl", "http://backend.sifen.svc.cluster.local:8081"))), 200);
        perform(post("/api/admin/connections/{id}/test", id).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200);

        JsonNode events = body(perform(get("/api/admin/audit").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200));
        assertThat(events.findValuesAsString("action"))
                .contains("connection.created", "connection.updated", "connection.tested");
        JsonNode update = findEvent(events, "connection.updated");
        assertThat(update.get("correlationId").asString()).isEqualTo("corr-update-1");
        assertThat(update.get("after").get("credentialsChanged").asBoolean()).isFalse();
        assertThat(update.get("before").get("name").asString()).isEqualTo("sifen-pilot");
        assertThat(events.toString()).doesNotContain(SECRET_KEY);
    }

    @Test
    void should_keep_credentials_when_update_omits_them() {
        String id = body(create("http://backend.sifen.svc.cluster.local:8081", 201)).get("id").asString();
        byte[] before = sealed(id);

        perform(put("/api/admin/connections/{id}", id).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "sifen-pilot", "baseUrl", "http://backend.sifen.svc.cluster.local:9000"))), 200);

        assertThat(sealed(id)).isEqualTo(before);
        JsonNode tested = body(perform(post("/api/admin/connections/{id}/test", id).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200));
        assertThat(tested.get("detail").asString()).doesNotContain("Credenciales ilegibles");
    }

    @Test
    void should_return_409_when_name_is_repeated_for_same_connector() {
        create("http://backend.sifen.svc.cluster.local:8081", 201);

        JsonNode problem = body(create("http://backend.sifen.svc.cluster.local:8081", 409));

        assertThat(problem.get("code").asString()).isEqualTo("connection-name-taken");
    }

    private MvcResult create(String baseUrl, int expected) {
        return perform(post("/api/admin/connections").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("connector", "SIFEN", "name", "sifen-pilot", "baseUrl", baseUrl,
                        "credentials", Map.of("accessKey", "ak-live", "secretKey", SECRET_KEY),
                        "settings", Map.of("hmacRequired", true)))), expected);
    }

    private byte[] sealed(String id) {
        return jdbc.sql("SELECT credentials_enc FROM connections WHERE id = :id").param("id", UUID.fromString(id))
                .query(byte[].class).single();
    }

    private static JsonNode findEvent(JsonNode events, String action) {
        for (JsonNode event : events) {
            if (action.equals(event.get("action").asString())) {
                return event;
            }
        }
        throw new AssertionError("sin evento " + action);
    }
}
