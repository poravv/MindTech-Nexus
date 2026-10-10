package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.operation.OperationStep;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationRepository {

    /** Console filters; every field is optional. {@code q} matches id, idempotency key or correlation id. */
    record Filter(Flow flow, OperationStatus status, Instant from, Instant to, String q) {
    }

    record StatusFlowCount(OperationStatus status, Flow flow, long count) {
    }

    Optional<Operation> findByKey(TenantId tenantId, OperationKind kind, String idempotencyKey);

    /** Inserts unless {@code (tenant, kind, key)} already exists; false means another request won the race. */
    boolean insertIfAbsent(Operation operation);

    Optional<Operation> findById(TenantId tenantId, UUID id);

    /** Newest first. Empty tenant means every tenant (platform_admin only). */
    List<Operation> find(Optional<TenantId> tenantId, Filter filter, int limit, int offset);

    /** Operations of the tenant not yet finished (PENDING, RETRY, RUNNING): the backpressure measure. */
    long countUnfinished(TenantId tenantId);

    /** True while an operation of {@code kind} whose integration targets {@code connectionId} is not finished. */
    boolean hasUnfinished(TenantId tenantId, OperationKind kind, UUID targetConnectionId);

    /** Operations created since {@code from}, grouped by status and flow. Empty tenant means every tenant. */
    List<StatusFlowCount> countSince(Optional<TenantId> tenantId, Instant from);

    /** Current size of the dead-letter queue, regardless of age. */
    long countDead(Optional<TenantId> tenantId);

    /**
     * Compare-and-set: writes {@code next} only if the row still matches {@code expected} (same status,
     * lock holder and attempts). A worker whose lease was rescued and re-claimed gets false and its late
     * result is discarded. Always releases the lease.
     */
    boolean update(Operation expected, Operation next);

    void insertStep(OperationStep step);

    List<OperationStep> findSteps(TenantId tenantId, UUID operationId);
}
