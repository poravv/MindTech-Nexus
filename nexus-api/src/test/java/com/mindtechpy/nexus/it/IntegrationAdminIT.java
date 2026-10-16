package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.identity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class IntegrationAdminIT extends AbstractOperationIT {

    @Test
    void should_create_enabled_integration_with_spec_defaults_when_policy_is_omitted() {
        JsonNode created = createIntegration(tenant, admin, targetConnection, Map.of("maxAttempts", 7), 201);

        assertThat(created.get("state").asString()).isEqualTo("ENABLED");
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("policy").get("maxAttempts").asInt()).isEqualTo(7);
        assertThat(created.get("policy").get("burst").asInt()).isEqualTo(5);
        assertThat(created.get("policy").get("readTimeoutMs").asInt()).isEqualTo(150_000);
    }

    @Test
    void should_return_422_when_policy_is_invalid() {
        JsonNode problem = createIntegration(tenant, admin, targetConnection, Map.of("maxInFlight", 0), 422);
        JsonNode tooSlow = createIntegration(tenant, admin, targetConnection, Map.of("readTimeoutMs", 600_000), 422);

        assertThat(problem.get("code").asString()).isEqualTo("invalid-policy");
        assertThat(tooSlow.get("code").asString()).isEqualTo("invalid-policy");
    }

    @Test
    void should_reconfigure_bump_version_and_audit_when_integration_is_updated() {
        JsonNode created = createIntegration(tenant, admin, targetConnection, Map.of(), 201);
        String id = created.get("id").asString();

        JsonNode updated = body(perform(put("/api/admin/integrations/{id}", id).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("flow", "CATALOG", "code", "catalog-main", "targetConnectionId", targetConnection.toString(),
                        "policy", Map.of("ratePerSecond", 5, "scheduleEverySeconds", 300)))), 200));

        assertThat(updated.get("code").asString()).isEqualTo("catalog-main");
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("policy").get("scheduleEverySeconds").asInt()).isEqualTo(300);
        JsonNode audit = body(perform(get("/api/admin/audit").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200));
        assertThat(audit.findValuesAsString("action")).contains("integration.created", "integration.updated");
    }

    @Test
    void should_return_409_when_a_second_catalog_integration_targets_the_same_connection() {
        createIntegration(tenant, admin, targetConnection, Map.of(), 201);

        JsonNode problem = createIntegration(tenant, admin, targetConnection, Map.of(), 409);

        assertThat(problem.get("code").asString()).isEqualTo("catalog-target-in-use");
    }

    @Test
    void should_let_exactly_one_create_win_when_two_catalog_integrations_target_the_same_connection_at_once() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> create = () -> {
            go.await();
            Map<String, Object> request = Map.of("flow", "CATALOG", "code", "catalog-" + UUID.randomUUID().toString().substring(0, 8),
                    "targetConnectionId", targetConnection.toString(), "policy", Map.of());
            return mvc.perform(post("/api/admin/integrations").param("tenantId", tenant.toString())
                    .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request))).andReturn().getResponse().getStatus();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = List.of(pool.submit(create), pool.submit(create));
            go.countDown();
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_return_409_when_code_is_repeated_in_the_tenant() {
        JsonNode created = createIntegration(tenant, admin, targetConnection, Map.of(), 201);

        JsonNode problem = body(perform(put("/api/admin/integrations/{id}", UUID.fromString(
                        createIntegration(Map.of()).toString()))
                .param("tenantId", tenant.toString()).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", created.get("code").asString(), "targetConnectionId", targetConnection.toString()))), 409));

        assertThat(problem.get("code").asString()).isEqualTo("integration-code-taken");
    }

    @Test
    void should_forbid_integration_changes_when_role_is_operator_or_viewer() {
        createIntegration(tenant, tokenFor(Role.OPERATOR, tenant), targetConnection, Map.of(), 403);
        createIntegration(tenant, tokenFor(Role.VIEWER, tenant), targetConnection, Map.of(), 403);
        createIntegration(tenant, tokenFor(Role.TENANT_ADMIN, tenant), targetConnection, Map.of(), 201);
    }
}
