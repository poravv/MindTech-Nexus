package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;

import java.time.Duration;

/** Engine metrics of spec §13. Labels are enums and step names only: never tenant ids. */
public interface OperationMetrics {

    void operationCreated(OperationKind kind);

    /** Terminal status; {@code DEAD} also counts as a dead letter. */
    void operationFinished(OperationKind kind, OperationStatus status, Duration sinceCreation);

    void stepFinished(Flow flow, String step, Duration duration);

    void rateLimited(Flow flow);
}
