package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.shared.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Semantics of spec §7.3: what each state dispatches, what it accepts and who may set it. */
class IntegrationStateIT extends AbstractOperationIT {

    @Autowired
    private MeterRegistry meters;

    @Test
    void should_hold_operations_without_spending_attempts_when_paused_and_resume_without_replay_when_enabled() {
        UUID integration = createIntegration(fastPolicy());
        Operation queued = start(integration, "paused-1");
        changeState(integration, tokenFor(Role.OPERATOR, tenant), "PAUSED", 200);

        drain();
        Operation acceptedWhilePaused = start(integration, "paused-2");
        drain();

        assertThat(reload(queued.id())).satisfies(op -> {
            assertThat(op.status()).isEqualTo(OperationStatus.PENDING);
            assertThat(op.attempts()).isZero();
        });
        assertThat(saga.callsFor(queued.id())).isZero();
        assertThat(meters.get("nexus.queue.depth").tag("status", "PENDING").gauge().value()).isGreaterThanOrEqualTo(2);

        changeState(integration, tokenFor(Role.OPERATOR, tenant), "ENABLED", 200);
        drain();

        assertThat(reload(queued.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(reload(acceptedWhilePaused.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
    }

    @Test
    void should_defer_without_spending_attempt_when_integration_is_paused_after_the_claim() {
        UUID integration = createIntegration(fastPolicy());
        Operation started = start(integration, "late-pause");
        Operation claimed = claimOnly(started.id(), "it-worker");
        changeState(integration, admin, "PAUSED", 200);

        runner.run(claimed);

        Operation deferred = reload(started.id());
        assertThat(deferred.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(deferred.attempts()).isZero();
        assertThat(saga.callsFor(started.id())).isZero();
    }

    @Test
    void should_reject_new_operations_and_finish_existing_ones_when_draining() {
        UUID integration = createIntegration(fastPolicy());
        String client = clientToken(tenant, "operations:read");
        startViaApi(client, integration, "drain-1", Map.of("n", 1), 202);
        changeState(integration, admin, "DRAINING", 200);

        JsonNode problem = body(startViaApi(client, integration, "drain-2", Map.of("n", 2), 409));
        startViaApi(client, integration, "drain-1", Map.of("n", 1), 200);
        drain();

        assertThat(problem.get("code").asString()).isEqualTo("integration-draining");
        Operation existing = operations.findByKey(new TenantId(tenant), OperationKind.CATALOG_SYNC, "drain-1").orElseThrow();
        assertThat(existing.status()).isEqualTo(OperationStatus.SUCCEEDED);
    }

    @Test
    void should_neither_dispatch_nor_accept_when_disabled_and_reserve_disabling_to_admins() {
        UUID integration = createIntegration(fastPolicy());
        String client = clientToken(tenant, "operations:read");
        Operation queued = start(integration, "disabled-1");

        changeState(integration, tokenFor(Role.OPERATOR, tenant), "DISABLED", 403);
        changeState(integration, tokenFor(Role.TENANT_ADMIN, tenant), "DISABLED", 200);
        drain();

        assertThat(reload(queued.id()).status()).isEqualTo(OperationStatus.PENDING);
        assertThat(body(startViaApi(client, integration, "disabled-2", Map.of(), 409)).get("code").asString())
                .isEqualTo("integration-disabled");
    }

    @Test
    void should_allow_only_platform_admin_to_enter_or_leave_emergency_stop() {
        UUID integration = createIntegration(fastPolicy());
        String client = clientToken(tenant, "operations:read");
        String tenantAdmin = tokenFor(Role.TENANT_ADMIN, tenant);
        Operation queued = start(integration, "emergency-1");

        changeState(integration, tenantAdmin, "EMERGENCY_STOP", 403);
        changeState(integration, admin, "EMERGENCY_STOP", 200);
        drain();

        assertThat(reload(queued.id()).status()).isEqualTo(OperationStatus.PENDING);
        assertThat(body(startViaApi(client, integration, "emergency-2", Map.of(), 409)).get("code").asString())
                .isEqualTo("integration-emergency-stop");
        changeState(integration, tenantAdmin, "ENABLED", 403);

        changeState(integration, admin, "ENABLED", 200);
        drain();
        assertThat(reload(queued.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
    }

    @Test
    void should_require_reason_and_audit_it_when_state_changes() {
        UUID integration = createIntegration(fastPolicy());

        perform(post("/api/admin/integrations/{id}/state", integration).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("state", "PAUSED", "reason", " "))), 400);
        perform(post("/api/admin/integrations/{id}/state", integration).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("state", "PAUSED", "reason", "Mantenimiento POS"))), 200);

        JsonNode audit = body(perform(get("/api/admin/audit").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200));
        JsonNode change = null;
        for (JsonNode event : audit) {
            if ("integration.state-changed".equals(event.get("action").asString())) {
                change = event;
            }
        }
        assertThat(change).isNotNull();
        assertThat(change.get("before").get("state").asString()).isEqualTo("ENABLED");
        assertThat(change.get("after").get("state").asString()).isEqualTo("PAUSED");
        assertThat(change.get("after").get("reason").asString()).isEqualTo("Mantenimiento POS");
    }
}
