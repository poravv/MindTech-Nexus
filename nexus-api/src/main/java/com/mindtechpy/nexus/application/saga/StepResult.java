package com.mindtechpy.nexus.application.saga;

import com.mindtechpy.nexus.domain.operation.ConnectorError;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of one step. {@code result}, when not null, replaces the operation result (pass the merged
 * map to keep earlier values); {@code externalRef} is recorded on the step (CDC, sale id…).
 */
public sealed interface StepResult {

    /** The step succeeded and the operation is done. */
    record Completed(String externalRef, Map<String, Object> result) implements StepResult {
    }

    /** The step succeeded; {@code nextStep} runs after {@code delay} with a fresh attempt budget. */
    record Next(String nextStep, Duration delay, String externalRef, Map<String, Object> result) implements StepResult {
        public Next {
            Objects.requireNonNull(nextStep, "nextStep");
            delay = delay == null ? Duration.ZERO : delay;
        }
    }

    /** The step failed; the engine decides retry, FAILED or DEAD from {@code error.kind()}. */
    record Failed(ConnectorError error) implements StepResult {
        public Failed {
            Objects.requireNonNull(error, "error");
        }
    }
}
