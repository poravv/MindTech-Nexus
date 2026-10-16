package com.mindtechpy.nexus.domain.operation;

import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A durable unit of work, identified for idempotency by {@code (tenant, kind, idempotencyKey)}.
 * {@code currentStep} only advances when a step succeeds, so a retried or replayed operation resumes
 * at the step that failed and never re-runs the ones that already succeeded. {@code result} carries
 * what earlier steps produced (e.g. remote references) to the following ones.
 */
public record Operation(UUID id, TenantId tenantId, UUID integrationId, OperationKind kind, String idempotencyKey,
                        String requestHash, OperationStatus status, String currentStep, int attempts, int maxAttempts,
                        Instant nextRunAt, String lockedBy, String correlationId, Map<String, Object> payload,
                        Map<String, Object> result, ConnectorError lastError, String deadReason, Instant createdAt,
                        Instant updatedAt, Instant finishedAt) {

    private static final Pattern KEY = Pattern.compile("^[\\x21-\\x7E]{1,200}$");

    public static Operation create(TenantId tenantId, UUID integrationId, OperationKind kind, String idempotencyKey,
                                   Map<String, Object> payload, int maxAttempts, String correlationId, Instant now) {
        if (kind == null) {
            throw DomainException.invalid("invalid-kind", "El tipo de operación es obligatorio");
        }
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw DomainException.invalid("invalid-idempotency-key",
                    "La clave idempotente debe tener 1-200 caracteres visibles sin espacios");
        }
        Map<String, Object> body = payload == null ? Map.of() : payload;
        return new Operation(UUID.randomUUID(), tenantId, integrationId, kind, idempotencyKey, RequestHash.of(body),
                OperationStatus.PENDING, null, 0, maxAttempts, now, null, correlationId, body, null, null, null,
                now, now, null);
    }

    public Flow flow() {
        return kind.flow();
    }

    /** True when the last attempt ended without knowing whether the remote side applied it: verify first. */
    public boolean needsVerification() {
        return lastError != null && lastError.kind() == ErrorKind.UNCERTAIN;
    }

    public Operation atStep(String step) {
        return next(status, step, attempts, nextRunAt, result, lastError, deadReason, finishedAt);
    }

    /** Back to the queue without consuming an attempt (integration held or no capacity). */
    public Operation deferred(Instant runAt) {
        return next(OperationStatus.RETRY, currentStep, attempts - 1, runAt, result, lastError, null, null);
    }

    /** Same state, due at {@code runAt}: a remote system announced a change worth checking now. */
    public Operation dueAt(Instant runAt) {
        return next(status, currentStep, attempts, runAt, result, lastError, deadReason, finishedAt);
    }

    public Operation retrying(ConnectorError error, Instant runAt, boolean countsAttempt) {
        return next(OperationStatus.RETRY, currentStep, countsAttempt ? attempts : attempts - 1, runAt, result, error,
                null, null);
    }

    /** The current step succeeded and {@code step} runs next with a fresh attempt budget. */
    public Operation advanced(String step, Map<String, Object> newResult, Instant runAt) {
        return next(OperationStatus.PENDING, step, 0, runAt, newResult == null ? result : newResult, null, null, null);
    }

    public Operation succeeded(Map<String, Object> newResult, Instant now) {
        return next(OperationStatus.SUCCEEDED, currentStep, attempts, nextRunAt, newResult == null ? result : newResult,
                null, null, now);
    }

    public Operation failed(ConnectorError error, Instant now) {
        return next(OperationStatus.FAILED, currentStep, attempts, nextRunAt, result, error, null, now);
    }

    /** The saga undid the remote effects of this ended operation: {@code result.compensated = true}. */
    public Operation compensated() {
        Map<String, Object> marked = new HashMap<>(result == null ? Map.of() : result);
        marked.put("compensated", true);
        return next(status, currentStep, attempts, nextRunAt, marked, lastError, deadReason, finishedAt);
    }

    public Operation dead(String reason, ConnectorError error, Instant now) {
        return next(OperationStatus.DEAD, currentStep, attempts, nextRunAt, result, error, reason, now);
    }

    /** Only dead operations are replayed; the idempotency key and the succeeded steps are kept. */
    public Operation replayed(Instant now) {
        if (status != OperationStatus.DEAD) {
            throw DomainException.conflict("operation-not-dead", "Solo se pueden reprocesar operaciones DEAD");
        }
        return next(OperationStatus.PENDING, currentStep, 0, now, result, lastError, null, null);
    }

    /** A human closes a dead operation without replaying it; it leaves the DLQ and never runs again. */
    public Operation discarded(Instant now) {
        if (status != OperationStatus.DEAD) {
            throw DomainException.conflict("operation-not-dead", "Solo se pueden descartar operaciones DEAD");
        }
        return next(OperationStatus.DISCARDED, currentStep, attempts, nextRunAt, result, lastError, deadReason, now);
    }

    /**
     * A FAILED operation its client asks for again: back to the queue at the failed step with a fresh budget.
     * {@code lastError} is kept, so the saga treats it as a resend and verifies the remote state first.
     */
    public Operation retriedAfterFailure(Instant now) {
        if (status != OperationStatus.FAILED) {
            throw DomainException.conflict("operation-not-failed", "Solo se reintentan operaciones FAILED");
        }
        return next(OperationStatus.PENDING, currentStep, 0, now, result, lastError, null, null);
    }

    private Operation next(OperationStatus newStatus, String step, int newAttempts, Instant runAt,
                           Map<String, Object> newResult, ConnectorError error, String reason, Instant finished) {
        return new Operation(id, tenantId, integrationId, kind, idempotencyKey, requestHash, newStatus, step, newAttempts,
                maxAttempts, runAt, null, correlationId, payload, newResult, error, reason, createdAt, updatedAt, finished);
    }
}
