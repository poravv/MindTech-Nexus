package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.operation.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** IDOR checks (ADR-005) on operations and integrations: other tenants' resources look nonexistent. */
class OperationIsolationIT extends AbstractOperationIT {

    private UUID tenantB;
    private UUID integrationB;
    private Operation operationB;
    private Operation operationA;

    @BeforeEach
    void setUpTwoTenants() {
        UUID integrationA = createIntegration(fastPolicy());
        operationA = start(integrationA, "own-op");
        UUID tenantA = tenant;
        UUID connectionA = targetConnection;

        tenantB = createTenant(admin);
        UUID connectionB = UUID.fromString(body(perform(post("/api/admin/connections").param("tenantId", tenantB.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("connector", "GIFTHUB", "name", "gifthub-b",
                        "baseUrl", "http://api.comercio-central.svc.cluster.local:3000",
                        "credentials", Map.of("apiKey", "gh_live_b")))), 201)).get("id").asString());
        integrationB = UUID.fromString(createIntegration(tenantB, admin, connectionB, fastPolicy(), 201).get("id").asString());
        tenant = tenantB;
        operationB = start(integrationB, "foreign-op");
        tenant = tenantA;
        targetConnection = connectionA;
    }

    @Test
    void should_return_404_when_machine_client_reads_operation_of_other_tenant() {
        String clientA = clientToken(tenant, "operations:read");

        perform(get("/api/v1/operations/{id}", operationB.id()).header("Authorization", bearer(clientA)), 404);
        JsonNode own = body(perform(get("/api/v1/operations/{id}", operationA.id()).header("Authorization", bearer(clientA)), 200));

        assertThat(own.get("status").asString()).isEqualTo("PENDING");
        assertThat(own.has("payload")).isFalse();
    }

    @Test
    void should_return_403_when_client_lacks_operations_scope_or_a_console_token_calls_runtime() {
        String fiscalOnly = clientToken(tenant, "fiscal:read");

        JsonNode problem = body(perform(get("/api/v1/operations/{id}", operationA.id())
                .header("Authorization", bearer(fiscalOnly)), 403));

        assertThat(problem.get("code").asString()).isEqualTo("insufficient-scope");
        perform(get("/api/v1/operations/{id}", operationA.id()).header("Authorization", bearer(admin)), 403);
    }

    @Test
    void should_return_404_when_tenant_user_reads_or_replays_operation_of_other_tenant() {
        String adminA = tokenFor(Role.TENANT_ADMIN, tenant);

        perform(get("/api/admin/operations/{id}", operationB.id()).header("Authorization", bearer(adminA)), 404);
        perform(post("/api/admin/operations/{id}/replay", operationB.id()).header("Authorization", bearer(adminA)), 404);
        perform(get("/api/admin/operations").param("tenantId", tenantB.toString()).header("Authorization", bearer(adminA)), 403);
        JsonNode listOfA = body(perform(get("/api/admin/operations").header("Authorization", bearer(adminA)), 200));

        assertThat(listOfA.findValuesAsString("id")).contains(operationA.id().toString()).doesNotContain(operationB.id().toString());
    }

    @Test
    void should_return_404_when_tenant_user_touches_integration_or_connection_of_other_tenant() {
        String adminA = tokenFor(Role.TENANT_ADMIN, tenant);

        perform(get("/api/admin/integrations/{id}", integrationB).header("Authorization", bearer(adminA)), 404);
        perform(put("/api/admin/integrations/{id}", integrationB).header("Authorization", bearer(adminA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", "hijack", "targetConnectionId", targetConnection.toString()))), 404);
        perform(post("/api/admin/integrations/{id}/state", integrationB).header("Authorization", bearer(adminA))
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("state", "PAUSED", "reason", "x"))), 404);
        UUID connectionOfB = UUID.fromString(body(perform(get("/api/admin/integrations/{id}", integrationB)
                .param("tenantId", tenantB.toString()).header("Authorization", bearer(admin)), 200))
                .get("targetConnectionId").asString());
        createIntegration(tenant, adminA, connectionOfB, Map.of(), 404);
    }
}
