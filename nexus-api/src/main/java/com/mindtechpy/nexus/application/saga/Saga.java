package com.mindtechpy.nexus.application.saga;

import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.OperationKind;

/**
 * A business flow run by the durable engine, one step per call. Implementations (fiscal in S3, orders
 * and catalog in S5) are registered as beans; the engine picks the one whose {@link #kind()} matches the
 * operation (one saga per kind).
 *
 * <p>Contract: {@link #execute} runs exactly the step named by {@code context.step()}, calls the remote
 * system at most once with the operation's stable idempotency key, validates the real effect and
 * classifies any failure as a {@link ConnectorError}. It never touches the operation row: the engine
 * persists the outcome, enforces gates (integration state, rate limit, credentials, attempts) and
 * schedules retries. After an {@code UNCERTAIN} failure the next call has
 * {@code context.operation().needsVerification()} true, so the saga verifies before resending.
 */
public interface Saga {

    OperationKind kind();

    /** Step run when the operation has not advanced yet. */
    String firstStep();

    StepResult execute(StepContext context);

    /**
     * The single compensation point, called once when the operation ends {@code FAILED} or {@code DEAD}
     * and before that is persisted. It undoes remote effects that need undoing (e.g. cancel the order in
     * GiftHub) based on {@code context.step()} and the cause; if it throws, the operation ends DEAD with
     * reason {@code compensation-failed}. Flows without remote effects to undo keep the default.
     *
     * @return true when it undid a remote effect, recorded as {@code result.compensated = true}
     */
    default boolean compensate(StepContext context, ConnectorError cause) {
        return false;
    }
}
