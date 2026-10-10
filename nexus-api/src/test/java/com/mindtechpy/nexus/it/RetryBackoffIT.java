package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Policy of these tests: maxAttempts 3, backoff 2 s doubling, capped at 8 s, ±20 % jitter. */
class RetryBackoffIT extends AbstractOperationIT {

    @Autowired
    private MeterRegistry meters;

    @Test
    void should_retry_with_growing_jittered_backoff_and_end_dead_when_transient_errors_exhaust_attempts() {
        UUID integration = createIntegration(fastPolicy());
        Operation op = start(integration, "transient");
        saga.behave(context -> failed(ErrorKind.TRANSIENT, null));
        double deadBefore = deadLetters();

        assertRetryScheduledWithin(op.id(), 1, Duration.ofMillis(1600), Duration.ofMillis(2400));
        makeDue(op.id());
        assertRetryScheduledWithin(op.id(), 2, Duration.ofMillis(3200), Duration.ofMillis(4800));
        makeDue(op.id());
        drain();

        Operation dead = reload(op.id());
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("attempts-exhausted");
        assertThat(dead.lastError().kind()).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(saga.callsFor(op.id())).isEqualTo(3);
        assertThat(operations.findSteps(dead.tenantId(), op.id())).hasSize(3)
                .allSatisfy(step -> assertThat(step.errorCode()).isEqualTo("TRANSIENT"));
        assertThat(saga.compensations).filteredOn(c -> c.operationId().equals(op.id())).singleElement()
                .satisfies(c -> assertThat(c.cause().kind()).isEqualTo(ErrorKind.TRANSIENT));
        assertThat(deadLetters()).isEqualTo(deadBefore + 1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'operation.dead' AND resource_id = :id")
                .param("id", op.id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void should_fail_once_without_retry_and_compensate_when_remote_rejects() {
        UUID integration = createIntegration(fastPolicy());
        Operation op = start(integration, "rejected");
        saga.behave(context -> failed(ErrorKind.REJECTED, null));

        drain();

        Operation failed = reload(op.id());
        assertThat(failed.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.finishedAt()).isNotNull();
        assertThat(saga.callsFor(op.id())).isEqualTo(1);
        assertThat(saga.compensations).filteredOn(c -> c.operationId().equals(op.id())).hasSize(1);
    }

    @Test
    void should_go_dead_at_once_when_credentials_fail_or_remote_reports_idempotency_conflict() {
        UUID integration = createIntegration(fastPolicy());
        Operation auth = start(integration, "auth");
        Operation conflict = start(integration, "conflict");
        saga.behave(context -> failed(context.operation().id().equals(auth.id()) ? ErrorKind.AUTH : ErrorKind.CONFLICT, null));

        drain();

        assertThat(reload(auth.id())).satisfies(op -> {
            assertThat(op.status()).isEqualTo(OperationStatus.DEAD);
            assertThat(op.deadReason()).isEqualTo("credentials");
        });
        assertThat(reload(conflict.id())).satisfies(op -> {
            assertThat(op.status()).isEqualTo(OperationStatus.DEAD);
            assertThat(op.deadReason()).isEqualTo("idempotency-conflict");
        });
    }

    @Test
    void should_wait_thirty_seconds_without_spending_attempt_when_remote_is_still_processing() {
        UUID integration = createIntegration(fastPolicy());
        Operation op = start(integration, "in-progress");
        saga.behave(context -> failed(ErrorKind.IN_PROGRESS, null));

        assertRetryScheduledWithin(op.id(), 0, Duration.ofSeconds(29), Duration.ofSeconds(31));
    }

    @Test
    void should_honour_retry_after_when_remote_rate_limits() {
        UUID integration = createIntegration(fastPolicy());
        Operation op = start(integration, "rate-limited");
        saga.behave(context -> failed(ErrorKind.RATE_LIMITED, Duration.ofSeconds(7)));

        assertRetryScheduledWithin(op.id(), 1, Duration.ofSeconds(6), Duration.ofSeconds(8));
    }

    @Test
    void should_signal_verification_to_saga_when_previous_attempt_was_uncertain() {
        UUID integration = createIntegration(fastPolicy());
        Operation op = start(integration, "uncertain");
        saga.behave(context -> context.operation().needsVerification()
                ? new StepResult.Completed("verified-ref", null)
                : failed(ErrorKind.UNCERTAIN, null));

        drain();
        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.RETRY);
        makeDue(op.id());
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(saga.calls).filteredOn(call -> call.operationId().equals(op.id()))
                .extracting(TestSagaConfig.ScriptedSaga.Call::verification).containsExactly(false, true);
        assertThat(operations.findSteps(op.tenantId(), op.id())).last()
                .satisfies(step -> assertThat(step.externalRef()).isEqualTo("verified-ref"));
    }

    private void assertRetryScheduledWithin(UUID id, int expectedAttempts, Duration min, Duration max) {
        Instant before = Instant.now();
        drain();
        Instant after = Instant.now();
        Operation retry = reload(id);
        assertThat(retry.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(retry.attempts()).isEqualTo(expectedAttempts);
        assertThat(retry.nextRunAt()).isBetween(before.plus(min), after.plus(max));
    }

    private static StepResult failed(ErrorKind kind, Duration retryAfter) {
        return new StepResult.Failed(new ConnectorError(kind, null, "simulado " + kind, null, retryAfter));
    }

    private double deadLetters() {
        Counter counter = meters.find("nexus.dead.letters").tag("flow", "CATALOG").counter();
        return counter == null ? 0 : counter.count();
    }
}
