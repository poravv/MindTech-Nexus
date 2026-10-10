package com.mindtechpy.nexus.domain.operation;

/**
 * Connector error classification shared by every connector (spec §7.4). The engine decides the
 * operation outcome from the kind alone.
 */
public enum ErrorKind {
    /** Connect timeout, 502/503/504, I/O: retry with backoff. */
    TRANSIENT,
    /** 429: retry honouring {@code Retry-After}. */
    RATE_LIMITED,
    /** The request may have taken effect (read timeout after sending): the saga must verify before resending. */
    UNCERTAIN,
    /** Validation or business rule: the operation FAILS, no retry. */
    REJECTED,
    /** Remote idempotency conflict: DEAD, needs a human. */
    CONFLICT,
    /** Remote still processing the same request: retry later without counting an attempt. */
    IN_PROGRESS,
    /** 401/403: DEAD until credentials are fixed. */
    AUTH,
    /** 402: the remote plan is exhausted; DEAD until the plan is fixed, then replayed. */
    PLAN_LIMIT
}
