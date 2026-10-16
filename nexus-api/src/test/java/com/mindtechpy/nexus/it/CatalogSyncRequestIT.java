package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.usecase.RunCatalogSync;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** {@code [NEXUS-CATALOG]} the POS asks for an immediate catalog sync with {@code POST /api/v1/catalog/sync}. */
class CatalogSyncRequestIT extends AbstractConnectorIT {

    @Autowired
    private RunCatalogSync runCatalogSync;

    @Test
    void should_run_one_sync_when_the_pos_asks_and_reuse_it_within_the_same_minute() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        stubOnePageCatalogAndBulk();
        String token = clientToken(tenant, "catalog:sync");

        JsonNode created = body(requestSync(token, 202));
        assertThat(created).hasSize(1);
        assertThat(created.get(0).get("integrationId").asString()).isEqualTo(integration.toString());
        assertThat(created.get(0).get("created").asBoolean()).isTrue();
        UUID operationId = UUID.fromString(created.get(0).get("operationId").asString());

        drain();

        assertThat(reload(operationId).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(reload(operationId).payload()).containsEntry("trigger", "pos");
        assertThat(giftHubRequests("PUT", "/products/bulk")).hasSize(1);

        JsonNode again = body(requestSync(token, 200));
        drain();

        assertThat(again.get(0).get("operationId").asString()).isEqualTo(operationId.toString());
        assertThat(again.get(0).get("created").asBoolean()).isFalse();
        assertThat(giftHubRequests("PUT", "/products/bulk")).hasSize(1);
    }

    @Test
    void should_require_the_catalog_sync_scope() {
        createIntegration("CATALOG", posConnection, giftHubConnection);

        requestSync(clientToken(tenant, "catalog:read"), 403);

        assertThat(catalogRuns()).isZero();
    }

    @Test
    void should_answer_409_while_a_run_to_the_gifthub_is_unfinished() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        runCatalogSync.scheduleDue();
        jdbc.sql("UPDATE operations SET status = 'RUNNING' WHERE integration_id = :i").param("i", integration).update();

        JsonNode problem = body(requestSync(clientToken(tenant, "catalog:sync"), 409));

        assertThat(problem.get("code").asString()).isEqualTo("catalog-run-in-progress");
        assertThat(catalogRuns()).isEqualTo(1);
    }

    @Test
    void should_answer_404_when_the_catalog_integration_is_paused_or_disabled() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        String token = clientToken(tenant, "catalog:sync");

        changeState(integration, admin, "PAUSED", 200);
        assertThat(body(requestSync(token, 404)).get("code").asString()).isEqualTo("catalog-integration-not-found");
        changeState(integration, admin, "DISABLED", 200);
        assertThat(body(requestSync(token, 404)).get("code").asString()).isEqualTo("catalog-integration-not-found");

        assertThat(catalogRuns()).isZero();
    }

    @Test
    void should_start_only_runs_of_the_token_tenant() {
        UUID ours = createIntegration("CATALOG", posConnection, giftHubConnection);
        UUID tenantA = tenant;
        tenant = createTenant(admin);
        UUID theirs = createIntegration("CATALOG", insertConnection(ConnectorType.POS, POS, Map.of("apiKey", "pv_live_test"), Map.of()),
                insertConnection(ConnectorType.GIFTHUB, GIFTHUB, Map.of("apiKey", "gh_live_test", "webhookSecret", WEBHOOK_SECRET), Map.of()));
        UUID tenantB = tenant;
        tenant = tenantA;

        JsonNode started = body(requestSync(clientToken(tenantA, "catalog:sync"), 202));

        assertThat(started).extracting(r -> r.get("integrationId").asString()).containsExactly(ours.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE tenant_id = :t").param("t", tenantB).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE integration_id = :i").param("i", theirs).query(Long.class).single()).isZero();
    }

    /** The owner just changed stock: a run the POS asks for skips the scheduler's backoff after DEAD runs. */
    @Test
    void should_run_when_the_pos_asks_even_while_the_scheduler_backs_off() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        Instant now = Instant.now();
        deadRun(integration, now.minus(Duration.ofMinutes(6)));
        deadRun(integration, now.minus(Duration.ofMinutes(5)));

        runCatalogSync.scheduleDue();
        assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE integration_id = :i AND idempotency_key LIKE :k")
                .param("i", integration).param("k", integration + ":2%").query(Long.class).single())
                .as("the scheduler still backs off").isZero();

        requestSync(clientToken(tenant, "catalog:sync"), 202);
    }

    private org.springframework.test.web.servlet.MvcResult requestSync(String token, int expected) {
        return perform(post("/api/v1/catalog/sync").header("Authorization", bearer(token)), expected);
    }

    private long catalogRuns() {
        return jdbc.sql("SELECT count(*) FROM operations WHERE tenant_id = :t AND kind = 'CATALOG_SYNC'")
                .param("t", tenant).query(Long.class).single();
    }

    private void deadRun(UUID integration, Instant finishedAt) {
        jdbc.sql("""
                INSERT INTO operations (id, tenant_id, integration_id, flow, kind, idempotency_key, request_hash, status,
                                        attempts, max_attempts, next_run_at, payload, created_at, updated_at, finished_at)
                VALUES (gen_random_uuid(), :t, :i, 'CATALOG', 'CATALOG_SYNC', :key, 'h', 'DEAD', 1, 5, :at, '{}', :at, :at, :at)""")
                .param("t", tenant).param("i", integration).param("key", integration + ":manual:" + UUID.randomUUID())
                .param("at", finishedAt.atOffset(ZoneOffset.UTC)).update();
    }

    private void stubOnePageCatalogAndBulk() {
        stubPos(get(urlPathEqualTo(pos("/api/integration/catalog"))).willReturn(okJson(toJson(Map.of(
                "items", List.of(Map.of("code", "P-0001", "name", "Chipa", "price", 5000, "stockQty", 0, "tracked", true,
                        "category", "Panificados", "updatedAt", "2026-10-16T10:00:00Z")),
                "page", 1, "limit", 500, "total", 1)))));
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).willReturn(okJson(
                "{\"results\":[{\"external_id\":\"P-0001\",\"result\":\"updated\",\"product_id\":\"gh-P-0001\"}]}")));
    }
}
