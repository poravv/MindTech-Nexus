package com.mindtechpy.nexus.domain.operation;

/**
 * Lifecycle of an operation (spec §7.1). {@code FAILED} is a final business outcome; {@code DEAD} is
 * the dead-letter state that needs a human and can be replayed.
 */
public enum OperationStatus {
    PENDING, RUNNING, RETRY, SUCCEEDED, FAILED, DEAD;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED || this == DEAD;
    }
}
