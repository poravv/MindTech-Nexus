package com.mindtechpy.nexus.domain.operation;

/**
 * Lifecycle of an operation (spec §7.1). {@code FAILED} is a final business outcome; {@code DEAD} is
 * the dead-letter state that needs a human, who replays it or discards it ({@code DISCARDED}, terminal).
 */
public enum OperationStatus {
    PENDING, RUNNING, RETRY, SUCCEEDED, FAILED, DEAD, DISCARDED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED || this == DEAD || this == DISCARDED;
    }
}
