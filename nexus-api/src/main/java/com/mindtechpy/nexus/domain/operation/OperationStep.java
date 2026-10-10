package com.mindtechpy.nexus.domain.operation;

import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.UUID;

/** One executed attempt of one step, kept as the operation history shown in the console. */
public record OperationStep(UUID id, TenantId tenantId, UUID operationId, String step, int attempt, Status status,
                            String externalRef, Integer responseCode, String errorCode, Instant startedAt,
                            Instant finishedAt) {

    public enum Status { SUCCEEDED, FAILED }
}
