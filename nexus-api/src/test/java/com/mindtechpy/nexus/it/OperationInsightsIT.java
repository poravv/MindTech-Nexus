package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.identity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Per-integration health and the operations time series of the console, both computed in Postgres. */
class OperationInsightsIT extends AbstractOperationIT {

    @Test
    void should_report_each_health_from_state_and_operations_of_the_window() {
        UUID healthy = integrationWith("SUCCEEDED", "SUCCEEDED");
        UUID degradedByDead = integrationWith("SUCCEEDED", "DEAD");
        UUID healthyWithRejections = integrationWith("SUCCEEDED", "FAILED");
        UUID degradedByOldDead = createIntegration(fastPolicy());
        set(start(degradedByOldDead, "old-dead").id(), "DEAD", Instant.now().minus(Duration.ofDays(3)));
        UUID discarded = integrationWith("DISCARDED");
        UUID progressing = integrationWith("SUCCEEDED", "RETRY");
        UUID draining = integrationWith();
        changeState(draining, admin, "DRAINING", 200);
        UUID suspended = integrationWith("DEAD");
        changeState(suspended, admin, "PAUSED", 200);
        UUID idle = integrationWith();

        Map<String, JsonNode> health = health(tenant, admin, 200);

        assertThat(health.get(healthy.toString()).get("health").asString()).isEqualTo("HEALTHY");
        assertThat(health.get(degradedByDead.toString()).get("health").asString()).isEqualTo("DEGRADED");
        assertThat(health.get(healthyWithRejections.toString()).get("health").asString()).isEqualTo("HEALTHY");
        assertThat(health.get(healthyWithRejections.toString()).get("byStatus").get("FAILED").asLong()).isEqualTo(1);
        assertThat(health.get(degradedByOldDead.toString()).get("health").asString()).isEqualTo("DEGRADED");
        assertThat(health.get(discarded.toString()).get("health").asString()).isEqualTo("HEALTHY");
        assertThat(health.get(progressing.toString()).get("health").asString()).isEqualTo("PROGRESSING");
        assertThat(health.get(draining.toString()).get("health").asString()).isEqualTo("PROGRESSING");
        assertThat(health.get(suspended.toString()).get("health").asString()).isEqualTo("SUSPENDED");
        assertThat(health.get(idle.toString()).get("health").asString()).isEqualTo("NO_ACTIVITY");
        assertThat(health).hasSize(9);
    }

    @Test
    void should_report_counts_dlq_and_last_failure_of_each_integration() {
        UUID integration = createIntegration(fastPolicy());
        set(start(integration, "ok").id(), "SUCCEEDED", Instant.now().minus(Duration.ofHours(2)));
        UUID dead = start(integration, "dead").id();
        set(dead, "DEAD", Instant.now().minus(Duration.ofHours(1)));
        jdbc.sql("UPDATE operations SET current_step = 'PUSH_PAGE', dead_reason = 'attempts-exhausted' WHERE id = :id")
                .param("id", dead).update();
        UUID failed = start(integration, "failed").id();
        set(failed, "FAILED", Instant.now().minus(Duration.ofHours(3)));
        jdbc.sql("UPDATE operations SET last_error = CAST(:error AS jsonb) WHERE id = :id")
                .param("error", "{\"kind\":\"REJECTED\",\"code\":\"unknown-product\"}").param("id", failed).update();
        set(start(integration, "old-dead").id(), "DEAD", Instant.now().minus(Duration.ofDays(3)));
        UUID quietWithOldDlq = createIntegration(fastPolicy());
        set(start(quietWithOldDlq, "older-dead").id(), "DEAD", Instant.now().minus(Duration.ofDays(3)));

        Map<String, JsonNode> health = health(tenant, admin, 200);
        JsonNode own = health.get(integration.toString());
        JsonNode quiet = health.get(quietWithOldDlq.toString());

        assertThat(own.get("byStatus").get("SUCCEEDED").asLong()).isEqualTo(1);
        assertThat(own.get("byStatus").get("DEAD").asLong()).isEqualTo(1);
        assertThat(own.get("byStatus").get("FAILED").asLong()).isEqualTo(1);
        assertThat(own.get("dead").asLong()).isEqualTo(2);
        assertThat(Instant.parse(own.get("lastOperationAt").asString())).isAfter(Instant.now().minus(Duration.ofMinutes(61)));
        JsonNode lastFailure = own.get("lastFailure");
        assertThat(lastFailure.get("operationId").asString()).isEqualTo(dead.toString());
        assertThat(lastFailure.get("status").asString()).isEqualTo("DEAD");
        assertThat(lastFailure.get("step").asString()).isEqualTo("PUSH_PAGE");
        assertThat(lastFailure.get("errorCode").asString()).isEqualTo("attempts-exhausted");
        assertThat(lastFailure.get("at").isNull()).isFalse();
        assertThat(quiet.get("health").asString()).isEqualTo("DEGRADED");
        assertThat(quiet.get("dead").asLong()).isEqualTo(1);
        assertThat(quiet.get("lastFailure").isNull()).isTrue();
        assertThat(quiet.get("lastOperationAt").isNull()).isTrue();
    }

    @Test
    void should_use_the_last_error_code_when_the_failure_has_no_dead_reason() {
        UUID integration = createIntegration(fastPolicy());
        UUID failed = start(integration, "failed").id();
        set(failed, "FAILED", Instant.now().minus(Duration.ofMinutes(5)));
        jdbc.sql("UPDATE operations SET last_error = CAST(:error AS jsonb) WHERE id = :id")
                .param("error", "{\"kind\":\"REJECTED\",\"code\":\"insufficient-stock\"}").param("id", failed).update();

        JsonNode lastFailure = health(tenant, admin, 200).get(integration.toString()).get("lastFailure");

        assertThat(lastFailure.get("status").asString()).isEqualTo("FAILED");
        assertThat(lastFailure.get("errorCode").asString()).isEqualTo("insufficient-stock");
    }

    @Test
    void should_not_expose_health_of_another_tenant() {
        UUID own = createIntegration(fastPolicy());
        UUID tenantB = createTenant(admin);
        UUID connectionB = gifthubConnection(tenantB);
        UUID foreign = UUID.fromString(createIntegration(tenantB, admin, connectionB, fastPolicy(), 201).get("id").asString());
        String adminA = tokenFor(Role.TENANT_ADMIN, tenant);

        Map<String, JsonNode> ofA = health(null, adminA, 200);

        assertThat(ofA).containsKey(own.toString()).doesNotContainKey(foreign.toString());
        perform(get("/api/admin/integrations/health").param("tenantId", tenantB.toString())
                .header("Authorization", bearer(adminA)), 403);
        JsonNode problem = body(perform(get("/api/admin/integrations/health").header("Authorization", bearer(admin)), 422));
        assertThat(problem.get("code").asString()).isEqualTo("tenant-required");
    }

    @Test
    void should_list_only_the_operations_of_the_requested_integration() {
        UUID own = createIntegration(fastPolicy());
        UUID sibling = createIntegration(fastPolicy());
        UUID mine = start(own, "mine").id();
        start(sibling, "sibling");

        JsonNode list = body(perform(get("/api/admin/operations").param("tenantId", tenant.toString())
                .param("integrationId", own.toString()).header("Authorization", bearer(admin)), 200));

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asString()).isEqualTo(mine.toString());
    }

    @Test
    void should_count_operations_per_hour_and_status_with_empty_buckets() {
        UUID integration = createIntegration(fastPolicy());
        Instant now = Instant.now();
        Instant threeHoursAgo = now.minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.HOURS);
        set(start(integration, "a").id(), "SUCCEEDED", threeHoursAgo.plus(Duration.ofMinutes(10)));
        set(start(integration, "b").id(), "SUCCEEDED", threeHoursAgo.plus(Duration.ofMinutes(50)));
        set(start(integration, "c").id(), "DEAD", threeHoursAgo.plus(Duration.ofMinutes(59)));
        set(start(integration, "d").id(), "SUCCEEDED", now.minus(Duration.ofDays(2)));

        JsonNode series = timeseries(tenant, admin, now.minus(Duration.ofHours(5)), "PT1H", 200);

        assertThat(series).hasSize(6);
        assertThat(Instant.parse(series.get(0).get("t").asString()))
                .isEqualTo(now.minus(Duration.ofHours(5)).truncatedTo(ChronoUnit.HOURS));
        JsonNode bucket = series.get(2);
        assertThat(Instant.parse(bucket.get("t").asString())).isEqualTo(threeHoursAgo);
        assertThat(bucket.get("byStatus").get("SUCCEEDED").asLong()).isEqualTo(2);
        assertThat(bucket.get("byStatus").get("DEAD").asLong()).isEqualTo(1);
        long total = 0;
        for (JsonNode point : series) {
            for (JsonNode count : point.get("byStatus")) {
                total += count.asLong();
            }
        }
        assertThat(total).isEqualTo(3);
    }

    @Test
    void should_bin_by_utc_day_and_by_five_minutes() {
        UUID integration = createIntegration(fastPolicy());
        Instant now = Instant.now();
        Instant yesterday = now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        set(start(integration, "a").id(), "SUCCEEDED", yesterday.plus(Duration.ofHours(23)));
        set(start(integration, "b").id(), "RETRY", now.minus(Duration.ofSeconds(1)));

        JsonNode daily = timeseries(tenant, admin, yesterday, "P1D", 200);
        JsonNode fiveMinutes = timeseries(tenant, admin, now.minus(Duration.ofMinutes(30)), "PT5M", 200);

        assertThat(daily).hasSize(2);
        assertThat(Instant.parse(daily.get(0).get("t").asString())).isEqualTo(yesterday);
        assertThat(daily.get(0).get("byStatus").get("SUCCEEDED").asLong()).isEqualTo(1);
        assertThat(daily.get(1).get("byStatus").get("RETRY").asLong()).isEqualTo(1);
        assertThat(fiveMinutes).hasSize(7);
        assertThat(Instant.parse(fiveMinutes.get(1).get("t").asString()).getEpochSecond() % 300).isZero();
    }

    @Test
    void should_reject_unknown_bucket_future_from_and_too_many_points() {
        Instant now = Instant.now();

        assertThat(problemCode(timeseries(tenant, admin, now.minus(Duration.ofHours(1)), "PT2H", 422))).isEqualTo("invalid-bucket");
        assertThat(problemCode(timeseries(tenant, admin, now.plus(Duration.ofHours(1)), "PT1H", 422))).isEqualTo("invalid-from");
        assertThat(problemCode(timeseries(tenant, admin, now.minus(Duration.ofDays(3)), "PT5M", 422))).isEqualTo("too-many-points");
        perform(get("/api/admin/operations/timeseries").param("tenantId", tenant.toString()).param("from", "ayer")
                .header("Authorization", bearer(admin)), 400);
        assertThat(timeseries(tenant, admin, null, "PT1H", 200)).hasSize(25);
    }

    @Test
    void should_not_count_operations_of_another_tenant_in_the_time_series() {
        set(start(createIntegration(fastPolicy()), "own").id(), "SUCCEEDED", Instant.now().minus(Duration.ofMinutes(5)));
        UUID tenantA = tenant;
        UUID tenantB = createTenant(admin);
        UUID integrationB = UUID.fromString(createIntegration(tenantB, admin, gifthubConnection(tenantB), fastPolicy(), 201)
                .get("id").asString());
        tenant = tenantB;
        set(start(integrationB, "foreign").id(), "DEAD", Instant.now().minus(Duration.ofMinutes(5)));
        tenant = tenantA;
        String adminA = tokenFor(Role.TENANT_ADMIN, tenantA);

        JsonNode series = timeseries(null, adminA, Instant.now().minus(Duration.ofHours(1)), "PT1H", 200);

        long dead = 0;
        long succeeded = 0;
        for (JsonNode point : series) {
            dead += point.get("byStatus").path("DEAD").asLong(0);
            succeeded += point.get("byStatus").path("SUCCEEDED").asLong(0);
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(dead).isZero();
        timeseries(tenantB, adminA, null, "PT1H", 403);
    }

    private UUID integrationWith(String... statuses) {
        UUID integration = createIntegration(fastPolicy());
        for (String status : statuses) {
            set(start(integration, UUID.randomUUID().toString()).id(), status, Instant.now().minus(Duration.ofMinutes(10)));
        }
        return integration;
    }

    private void set(UUID operationId, String status, Instant createdAt) {
        jdbc.sql("UPDATE operations SET status = :status, created_at = :createdAt WHERE id = :id")
                .param("status", status).param("createdAt", createdAt.atOffset(ZoneOffset.UTC)).param("id", operationId).update();
    }

    private UUID gifthubConnection(UUID tenantId) {
        return UUID.fromString(body(perform(post("/api/admin/connections").param("tenantId", tenantId.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("connector", "GIFTHUB", "name", "gifthub-b",
                        "baseUrl", "http://api.comercio-central.svc.cluster.local:3000",
                        "credentials", Map.of("apiKey", "gh_live_b")))), 201)).get("id").asString());
    }

    /** Health entries keyed by integration id. */
    private Map<String, JsonNode> health(UUID tenantId, String token, int expected) {
        var request = get("/api/admin/integrations/health").header("Authorization", bearer(token));
        if (tenantId != null) {
            request.param("tenantId", tenantId.toString());
        }
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode entry : body(perform(request, expected))) {
            byId.put(entry.get("integrationId").asString(), entry);
        }
        return byId;
    }

    private JsonNode timeseries(UUID tenantId, String token, Instant from, String bucket, int expected) {
        var request = get("/api/admin/operations/timeseries").param("bucket", bucket).header("Authorization", bearer(token));
        if (tenantId != null) {
            request.param("tenantId", tenantId.toString());
        }
        if (from != null) {
            request.param("from", from.toString());
        }
        return body(perform(request, expected));
    }

    private static String problemCode(JsonNode problem) {
        return problem.get("code").asString();
    }
}
