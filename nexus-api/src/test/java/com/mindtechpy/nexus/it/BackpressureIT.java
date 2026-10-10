package com.mindtechpy.nexus.it;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The test context caps unfinished operations at 150 per tenant. */
class BackpressureIT extends AbstractOperationIT {

    @Test
    void should_answer_429_with_retry_after_and_persist_nothing_when_tenant_queue_is_saturated() {
        UUID integration = createIntegration(fastPolicy());
        changeState(integration, admin, "PAUSED", 200);
        jdbc.sql("""
                        INSERT INTO operations (id, tenant_id, integration_id, flow, kind, idempotency_key, request_hash,
                                                status, max_attempts, next_run_at)
                        SELECT gen_random_uuid(), :tenant, :integration, 'CATALOG', 'CATALOG_SYNC', 'bulk-' || g, 'hash',
                               'PENDING', 3, now()
                        FROM generate_series(1, 150) g""")
                .param("tenant", tenant).param("integration", integration).update();
        String client = clientToken(tenant, "operations:read");

        MvcResult rejected = startViaApi(client, integration, "one-too-many", Map.of(), 429);

        assertThat(rejected.getResponse().getHeader("Retry-After")).isEqualTo("30");
        assertThat(body(rejected).get("code").asString()).isEqualTo("queue-saturated");
        assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE tenant_id = :t AND idempotency_key = 'one-too-many'")
                .param("t", tenant).query(Long.class).single()).isZero();
    }
}
