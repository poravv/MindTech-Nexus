package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.infrastructure.jobs.PayloadRetentionJob;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Retention deletes only CATALOG_SYNC runs that SUCCEEDED over 7 days ago, together with their steps. */
class CatalogRunRetentionIT extends AbstractOperationIT {

    @Test
    void should_delete_only_old_succeeded_catalog_runs_with_their_steps() {
        UUID integration = createIntegration(fastPolicy());
        UUID oldSucceeded = run(integration, "CATALOG_SYNC", "SUCCEEDED", 8);
        UUID recentSucceeded = run(integration, "CATALOG_SYNC", "SUCCEEDED", 6);
        UUID oldDead = run(integration, "CATALOG_SYNC", "DEAD", 8);
        UUID recentDead = run(integration, "CATALOG_SYNC", "DEAD", 6);
        UUID oldOrder = run(integration, "CREATE_ORDER", "SUCCEEDED", 8);

        new PayloadRetentionJob(jdbc).purge();

        assertThat(exists(oldSucceeded)).isFalse();
        assertThat(steps(oldSucceeded)).isZero();
        assertThat(exists(recentSucceeded)).isTrue();
        assertThat(steps(recentSucceeded)).isEqualTo(1);
        assertThat(exists(oldDead)).isTrue();
        assertThat(steps(oldDead)).isEqualTo(1);
        assertThat(exists(recentDead)).isTrue();
        assertThat(exists(oldOrder)).isTrue();
        assertThat(steps(oldOrder)).isEqualTo(1);
    }

    private UUID run(UUID integration, String kind, String status, int daysAgo) {
        UUID id = UUID.randomUUID();
        var at = Instant.now().minus(daysAgo, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO operations (id, tenant_id, integration_id, flow, kind, idempotency_key, request_hash, status,
                                        attempts, max_attempts, next_run_at, payload, created_at, updated_at, finished_at)
                VALUES (:id, :t, :i, 'CATALOG', :kind, :key, 'h', :status, 1, 5, :at, '{}', :at, :at, :at)""")
                .param("id", id).param("t", tenant).param("i", integration).param("kind", kind)
                .param("key", "ret-" + id).param("status", status).param("at", at).update();
        jdbc.sql("""
                INSERT INTO operation_steps (id, tenant_id, operation_id, step, attempt, status, started_at, finished_at)
                VALUES (gen_random_uuid(), :t, :id, 'push', 1, 'SUCCEEDED', :at, :at)""")
                .param("t", tenant).param("id", id).param("at", at).update();
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM operations WHERE id = :id").param("id", id).query(Long.class).single() == 1;
    }

    private long steps(UUID id) {
        return jdbc.sql("SELECT count(*) FROM operation_steps WHERE operation_id = :id").param("id", id).query(Long.class).single();
    }
}
