package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ReplayIT extends AbstractOperationIT {

    @Test
    void should_resume_at_failed_step_with_same_key_when_dead_operation_is_replayed() {
        UUID integration = createIntegration(fastPolicy());
        Operation op = start(integration, "replay-1");
        AtomicBoolean credentialsFixed = new AtomicBoolean(false);
        saga.behave(context -> switch (context.step()) {
            case "FIRST" -> new StepResult.Next("SECOND", Duration.ZERO, "ref-first", Map.of("first", "done"));
            default -> credentialsFixed.get()
                    ? new StepResult.Completed("ref-second", null)
                    : new StepResult.Failed(ConnectorError.of(ErrorKind.AUTH, "unauthorized", "401"));
        });
        drain();
        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.DEAD);

        credentialsFixed.set(true);
        String operator = tokenFor(Role.OPERATOR, tenant);
        JsonNode replayed = body(perform(post("/api/admin/operations/{id}/replay", op.id())
                .header("Authorization", bearer(operator)), 200));
        assertThat(replayed.get("status").asString()).isEqualTo("PENDING");
        assertThat(replayed.get("idempotencyKey").asString()).isEqualTo("replay-1");
        assertThat(replayed.get("attempts").asInt()).isZero();
        drain();

        Operation finished = reload(op.id());
        assertThat(finished.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(finished.result()).containsEntry("first", "done");
        assertThat(saga.calls).filteredOn(call -> call.operationId().equals(op.id()))
                .extracting(TestSagaConfig.ScriptedSaga.Call::step).containsExactly("FIRST", "SECOND", "SECOND");
        JsonNode detail = body(perform(get("/api/admin/operations/{id}", op.id()).header("Authorization", bearer(operator)), 200));
        assertThat(detail.get("id").asString()).isEqualTo(op.id().toString());
        assertThat(detail.get("result").get("first").asString()).isEqualTo("done");
        assertThat(detail.get("steps").findValuesAsString("status")).containsExactly("SUCCEEDED", "FAILED", "SUCCEEDED");
        assertThat(detail.get("steps").get(0).get("externalRef").asString()).isEqualTo("ref-first");
        JsonNode audit = body(perform(get("/api/admin/audit").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200));
        assertThat(audit.findValuesAsString("action")).contains("operation.dead", "operation.replayed");
    }

    @Test
    void should_count_by_status_and_flow_and_report_dlq_size_when_summary_is_requested() {
        UUID integration = createIntegration(fastPolicy());
        start(integration, "summary-dead");
        saga.behave(context -> new StepResult.Failed(ConnectorError.of(ErrorKind.AUTH, "unauthorized", "401")));
        drain();
        start(integration, "summary-pending");

        JsonNode summary = body(perform(get("/api/admin/operations/summary").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200));

        assertThat(summary.get("byStatus").get("DEAD").asLong()).isEqualTo(1);
        assertThat(summary.get("byStatus").get("PENDING").asLong()).isEqualTo(1);
        assertThat(summary.get("byFlow").get("CATALOG").asLong()).isEqualTo(2);
        assertThat(summary.get("dead").asLong()).isEqualTo(1);
        assertThat(summary.get("from").asString()).isNotBlank();
    }

    @Test
    void should_return_409_when_replaying_an_operation_that_is_not_dead() {
        UUID integration = createIntegration(fastPolicy());
        Operation op = start(integration, "replay-2");

        JsonNode problem = body(perform(post("/api/admin/operations/{id}/replay", op.id()).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 409));

        assertThat(problem.get("code").asString()).isEqualTo("operation-not-dead");
    }

    @Test
    void should_forbid_replay_when_role_only_reads() {
        UUID integration = createIntegration(fastPolicy());
        Operation op = start(integration, "replay-3");

        perform(post("/api/admin/operations/{id}/replay", op.id())
                .header("Authorization", bearer(tokenFor(Role.VIEWER, tenant))), 403);
        perform(post("/api/admin/operations/{id}/replay", op.id())
                .header("Authorization", bearer(tokenFor(Role.AUDITOR, tenant))), 403);
    }
}
