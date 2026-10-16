package com.mindtechpy.nexus.application.saga;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationMetrics;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.RateLimiterPort;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.operation.OperationStep;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * Runs the current step of a claimed operation (spec §7). Gates, in order: integration state (fresh
 * read), registered saga, decryptable credentials, attempt budget, rate limit / in-flight capacity. Then
 * the saga runs outside any transaction and its outcome is persisted with the step in one transaction,
 * fenced by the lease. {@link #endWithFailure} is the only place that compensates and ends an operation
 * FAILED or DEAD.
 */
public class SagaRunner {

    static final Duration HELD_RECHECK = Duration.ofSeconds(5);
    static final Duration NO_CAPACITY_DELAY = Duration.ofSeconds(1);
    static final Duration IN_PROGRESS_DELAY = Duration.ofSeconds(30);

    private static final System.Logger log = System.getLogger(SagaRunner.class.getName());

    private final Map<OperationKind, Saga> sagas = new EnumMap<>(OperationKind.class);
    private final OperationRepository operations;
    private final IntegrationRepository integrations;
    private final ConnectionRepository connections;
    private final SecretCipherPort cipher;
    private final RateLimiterPort rateLimiter;
    private final OperationMetrics metrics;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public SagaRunner(List<Saga> sagas, OperationRepository operations, IntegrationRepository integrations,
                      ConnectionRepository connections, SecretCipherPort cipher, RateLimiterPort rateLimiter,
                      OperationMetrics metrics, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        for (Saga saga : sagas) {
            if (this.sagas.put(saga.kind(), saga) != null) {
                throw new IllegalStateException("Hay dos sagas registradas para " + saga.kind());
            }
        }
        this.operations = operations;
        this.integrations = integrations;
        this.connections = connections;
        this.cipher = cipher;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    /** Runs one step of an operation this worker leased with {@code JobQueuePort.claim} and persists the outcome. */
    public void run(Operation claimed) {
        Integration integration = integrations.findById(claimed.tenantId(), claimed.integrationId())
                .orElseThrow(() -> new IllegalStateException("Integración inexistente: " + claimed.integrationId()));
        if (!integration.state().dispatches()) {
            persist(claimed, claimed.deferred(clock.now().plus(HELD_RECHECK)), null);
            return;
        }
        Saga saga = sagas.get(claimed.kind());
        if (saga == null) {
            endWithFailure(null, null, claimed, claimed.dead("saga-missing", null, clock.now()), null, null);
            return;
        }
        Operation working = claimed.currentStep() == null ? claimed.atStep(saga.firstStep()) : claimed;
        StepContext context;
        try {
            context = new StepContext(working, integration, endpoint(working.tenantId(), integration.sourceConnectionId()),
                    endpoint(working.tenantId(), integration.targetConnectionId()));
        } catch (DomainException e) {
            ConnectorError error = ConnectorError.of(ErrorKind.AUTH, "credentials-missing", e.getMessage());
            endWithFailure(null, null, claimed, working.dead("credentials-missing", error, clock.now()), error, null);
            return;
        }
        if (working.attempts() > working.maxAttempts()) {
            ConnectorError cause = working.lastError() != null ? working.lastError()
                    : ConnectorError.of(ErrorKind.UNCERTAIN, "lease-expired", "El worker se detuvo durante el paso");
            endWithFailure(saga, context, claimed, working.dead("attempts-exhausted", cause, clock.now()), cause, null);
            return;
        }
        if (!rateLimiter.tryAcquire(integration.id(), integration.policy())) {
            metrics.rateLimited(working.flow());
            persist(claimed, working.deferred(clock.now().plus(NO_CAPACITY_DELAY)), null);
            return;
        }
        Instant startedAt = clock.now();
        StepResult result;
        try {
            result = execute(saga, context);
        } finally {
            rateLimiter.release(integration.id());
        }
        apply(saga, context, claimed, result, startedAt);
    }

    private StepContext.Endpoint endpoint(TenantId tenantId, UUID connectionId) {
        if (connectionId == null) {
            return null;
        }
        Connection connection = connections.findById(tenantId, connectionId)
                .orElseThrow(() -> DomainException.invalid("credentials-missing", "La conexión " + connectionId + " no existe"));
        return new StepContext.Endpoint(connection, cipher.open(connection.sealedCredentials(), connection.credentialsAad()));
    }

    private StepResult execute(Saga saga, StepContext context) {
        Operation op = context.operation();
        log.log(INFO, "{0} op={1} tenant={2} step={3} attempt={4} inicio",
                op.flow().logPrefix(), op.id(), op.tenantId(), op.currentStep(), String.valueOf(op.attempts()));
        try {
            return saga.execute(context);
        } catch (RuntimeException e) {
            log.log(WARNING, op.flow().logPrefix() + " op=" + op.id() + " step=" + op.currentStep()
                    + " excepción no clasificada: se trata como UNCERTAIN", e);
            return new StepResult.Failed(ConnectorError.of(ErrorKind.UNCERTAIN, "unexpected-error", e.getClass().getSimpleName()));
        }
    }

    private void apply(Saga saga, StepContext context, Operation claimed, StepResult result, Instant startedAt) {
        Operation op = context.operation();
        Instant now = clock.now();
        metrics.stepFinished(op.flow(), op.currentStep(), Duration.between(startedAt, now));
        switch (result) {
            case StepResult.Completed done -> persist(claimed, op.succeeded(done.result(), now),
                    step(op, OperationStep.Status.SUCCEEDED, done.externalRef(), null, startedAt, now));
            case StepResult.Next next -> persist(claimed, op.advanced(next.nextStep(), next.result(), now.plus(next.delay())),
                    step(op, OperationStep.Status.SUCCEEDED, next.externalRef(), null, startedAt, now));
            case StepResult.Failed failed -> applyFailure(saga, context, claimed, failed.error(),
                    step(op, OperationStep.Status.FAILED, null, failed.error(), startedAt, now), now);
        }
    }

    private void applyFailure(Saga saga, StepContext context, Operation claimed, ConnectorError error,
                              OperationStep step, Instant now) {
        Operation op = context.operation();
        switch (error.kind()) {
            case REJECTED -> endWithFailure(saga, context, claimed, op.failed(error, now), error, step);
            case CONFLICT -> endWithFailure(saga, context, claimed, op.dead("idempotency-conflict", error, now), error, step);
            case AUTH -> endWithFailure(saga, context, claimed, op.dead("credentials", error, now), error, step);
            case PLAN_LIMIT -> endWithFailure(saga, context, claimed, op.dead("plan-limit", error, now), error, step);
            case EXPIRED, CONFIGURATION -> endWithFailure(saga, context, claimed, op.dead(error.code(), error, now), error, step);
            case IN_PROGRESS -> persist(claimed, op.retrying(error,
                    now.plus(error.retryAfter() != null ? error.retryAfter() : IN_PROGRESS_DELAY), false), step);
            case TRANSIENT, RATE_LIMITED, UNCERTAIN -> {
                if (op.attempts() >= op.maxAttempts()) {
                    endWithFailure(saga, context, claimed, op.dead("attempts-exhausted", error, now), error, step);
                } else {
                    persist(claimed, op.retrying(error, now.plus(retryDelay(context, error)), true), step);
                }
            }
        }
    }

    private static Duration retryDelay(StepContext context, ConnectorError error) {
        if (error.kind() == ErrorKind.RATE_LIMITED && error.retryAfter() != null) {
            return error.retryAfter();
        }
        return context.integration().policy().retryDelay(context.operation().attempts(), ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Single compensation point: asks the saga to undo remote effects (when there is a saga context) and
     * then persists the terminal status, with {@code result.compensated = true} when it undid them. A failing
     * compensation, or one that asks for manual reconciliation, turns the outcome into DEAD so a human reviews it.
     */
    private void endWithFailure(Saga saga, StepContext context, Operation claimed, Operation terminal,
                                ConnectorError cause, OperationStep step) {
        Operation outcome = terminal;
        if (saga != null) {
            try {
                if (saga.compensate(context, cause)) {
                    outcome = terminal.compensated();
                }
            } catch (ManualReconciliationRequired e) {
                log.log(WARNING, "{0} op={1} conciliación manual ({2})", terminal.flow().logPrefix(), terminal.id(), cause.code());
                outcome = terminal.dead(ManualReconciliationRequired.REASON, new ConnectorError(cause.kind(), cause.code(),
                        e.getMessage(), cause.httpStatus(), null), clock.now());
            } catch (RuntimeException e) {
                log.log(WARNING, terminal.flow().logPrefix() + " op=" + terminal.id() + " compensación fallida", e);
                outcome = terminal.dead("compensation-failed", cause, clock.now());
            }
        }
        persist(claimed, outcome, step);
    }

    private void persist(Operation claimed, Operation next, OperationStep step) {
        boolean saved = tx.inTransaction(() -> {
            if (!operations.update(claimed, next)) {
                return false;
            }
            if (step != null) {
                operations.insertStep(step);
            }
            if (next.status() == OperationStatus.FAILED || next.status() == OperationStatus.DEAD) {
                audit.append(AuditEvent.of(Actor.system(), next.tenantId(), "operation." + next.status().name().toLowerCase(),
                        "operation", next.id().toString(), null, describe(next), clock.now()));
            }
            return true;
        });
        if (!saved) {
            log.log(WARNING, "{0} op={1} lease perdido: el resultado de este worker se descarta",
                    next.flow().logPrefix(), next.id());
            return;
        }
        String error = next.lastError() == null ? "" : " error=" + next.lastError().kind() + ":" + next.lastError().code();
        String reason = next.deadReason() == null ? "" : " motivo=" + next.deadReason();
        log.log(INFO, "{0} op={1} tenant={2} step={3} attempt={4} -> {5}{6}{7}", next.flow().logPrefix(), next.id(),
                next.tenantId(), next.currentStep(), String.valueOf(claimed.attempts()), next.status(), error, reason);
        if (next.status().isFinished()) {
            metrics.operationFinished(next.kind(), next.status(), Duration.between(next.createdAt(), next.finishedAt()));
        }
    }

    private static OperationStep step(Operation op, OperationStep.Status status, String externalRef, ConnectorError error,
                                      Instant startedAt, Instant finishedAt) {
        return new OperationStep(UUID.randomUUID(), op.tenantId(), op.id(), op.currentStep(), op.attempts(), status,
                externalRef, error == null ? null : error.httpStatus(), error == null ? null : error.code(),
                startedAt, finishedAt);
    }

    private static Map<String, Object> describe(Operation op) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", op.status().name());
        view.put("step", op.currentStep());
        view.put("deadReason", op.deadReason());
        view.put("errorCode", op.lastError() == null ? null : op.lastError().code());
        return view;
    }
}
