package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.identity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** IDOR checks (ADR-005): the tenant always comes from the token; other tenants' data looks nonexistent. */
class TenantIsolationIT extends AbstractPostgresIT {

    private String platformAdmin;
    private UUID tenantA;
    private UUID tenantB;
    private String adminA;
    private String connectionOfB;

    @BeforeEach
    void setUp() {
        platformAdmin = platformAdminToken();
        tenantA = createTenant(platformAdmin);
        tenantB = createTenant(platformAdmin);
        adminA = tokenFor(Role.TENANT_ADMIN, tenantA);
        connectionOfB = body(perform(post("/api/admin/connections").param("tenantId", tenantB.toString())
                .header("Authorization", bearer(platformAdmin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("connector", "SIFEN", "name", "sifen-b",
                        "baseUrl", "http://backend.sifen.svc.cluster.local:8081",
                        "credentials", Map.of("accessKey", "ak-b", "secretKey", "sk-b")))), 201)).get("id").asString();
    }

    @Test
    void should_return_404_when_tenant_user_reads_or_changes_connection_of_other_tenant() {
        perform(get("/api/admin/connections/{id}", connectionOfB).header("Authorization", bearer(adminA)), 404);
        perform(post("/api/admin/connections/{id}/test", connectionOfB).header("Authorization", bearer(adminA)), 404);
        perform(put("/api/admin/connections/{id}", connectionOfB).header("Authorization", bearer(adminA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "hijack", "baseUrl", "http://x.svc.cluster.local"))), 404);

        JsonNode listOfA = body(perform(get("/api/admin/connections").header("Authorization", bearer(adminA)), 200));
        assertThat(listOfA.findValuesAsString("id")).doesNotContain(connectionOfB);
    }

    @Test
    void should_return_403_when_tenant_user_requests_other_tenant_explicitly() {
        JsonNode problem = body(perform(get("/api/admin/connections/{id}", connectionOfB).param("tenantId", tenantB.toString())
                .header("Authorization", bearer(adminA)), 403));

        assertThat(problem.get("code").asString()).isEqualTo("tenant-mismatch");
        perform(get("/api/admin/audit").param("tenantId", tenantB.toString()).header("Authorization", bearer(adminA)), 403);
        perform(get("/api/admin/users").param("tenantId", tenantB.toString()).header("Authorization", bearer(adminA)), 403);
    }

    @Test
    void should_not_reach_admin_api_when_machine_client_of_a_names_tenant_b() {
        JsonNode issued = body(perform(post("/api/admin/api-clients").header("Authorization", bearer(adminA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "POS A", "scopes", List.of("fiscal:read")))), 201));
        String basic = java.util.Base64.getEncoder().encodeToString(
                (issued.get("client").get("clientId").asString() + ":" + issued.get("clientSecret").asString()).getBytes());
        String clientToken = body(perform(post("/oauth/token").header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials"), 200))
                .get("access_token").asString();

        perform(get("/api/admin/connections/{id}", connectionOfB).param("tenantId", tenantB.toString())
                .header("Authorization", bearer(clientToken)), 403);
        assertThat(issued.get("client").get("tenantId").asString()).isEqualTo(tenantA.toString());
    }

    @Test
    void should_scope_audit_to_own_tenant_when_tenant_admin_lists_it() {
        JsonNode events = body(perform(get("/api/admin/audit").header("Authorization", bearer(adminA)), 200));

        assertThat(events.findValuesAsString("tenantId")).allMatch(tenantA.toString()::equals);
        assertThat(events.findValuesAsString("resourceId")).doesNotContain(connectionOfB);
    }

    @Test
    void should_require_explicit_tenant_when_platform_admin_acts_on_tenant_resources() {
        JsonNode problem = body(perform(get("/api/admin/connections").header("Authorization", bearer(platformAdmin)), 422));

        assertThat(problem.get("code").asString()).isEqualTo("tenant-required");
    }

    @Test
    void should_forbid_when_role_lacks_permission() {
        String viewerA = tokenFor(Role.VIEWER, tenantA);

        perform(post("/api/admin/connections").header("Authorization", bearer(viewerA)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("connector", "POS", "name", "pos", "baseUrl", "http://pos.svc.cluster.local",
                        "credentials", Map.of("apiKey", "k")))), 403);
        perform(get("/api/admin/tenants").header("Authorization", bearer(adminA)), 403);
        perform(get("/api/admin/audit").header("Authorization", bearer(viewerA)), 403);
        perform(post("/api/admin/users").header("Authorization", bearer(adminA)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", UUID.randomUUID() + "@x.test", "password", PASSWORD, "role", "platform_admin"))), 403);
    }
}
