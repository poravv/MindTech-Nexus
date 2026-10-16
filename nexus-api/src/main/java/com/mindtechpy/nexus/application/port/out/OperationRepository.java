package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.operation.OperationStep;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface OperationRepository {

    /** Console filters; every field is optional. {@code q} matches id, idempotency key or correlation id. */
    record Filter(UUID integrationId, Flow flow, OperationStatus status, Instant from, Instant to, String q) {
    }

    record StatusFlowCount(OperationStatus status, Flow flow, long count) {
    }

    record IntegrationStatusCount(UUID integrationId, OperationStatus status, long count, Instant lastCreatedAt) {
    }

    /** Latest {@code FAILED} or {@code DEAD} operation of an integration; {@code errorCode} is the dead reason or the last error code. */
    record LastFailure(UUID operationId, OperationStatus status, String step, String errorCode, Instant at) {
    }

    record BucketCount(Instant bucket, OperationStatus status, long count) {
    }

    record FinishedRun(OperationStatus status, Instant finishedAt) {
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

    /**
     * Holds until the current transaction ends a lock on catalog runs to {@code targetConnectionId}, so the check
     * {@link #hasUnfinished} and the insert of the next run cannot interleave with another trigger.
     */
    void lockCatalogTarget(UUID targetConnectionId);

    /** Operations created since {@code from}, grouped by status and flow. Empty tenant means every tenant. */
    List<StatusFlowCount> countSince(Optional<TenantId> tenantId, Instant from);

    /** Current size of the dead-letter queue, regardless of age. */
    long countDead(Optional<TenantId> tenantId);

    /** Operations of the tenant created since {@code from}, grouped by integration and status. */
    List<IntegrationStatusCount> countByIntegrationSince(TenantId tenantId, Instant from);

    /** Current dead-letter queue of the tenant per integration, regardless of age. */
    Map<UUID, Long> countDeadByIntegration(TenantId tenantId);

    /** Per integration id, its newest failure among the operations created since {@code from}. */
    Map<UUID, LastFailure> lastFailuresSince(TenantId tenantId, Instant from);

    /** The latest {@code limit} finished operations of {@code kind} of an integration, newest finish first. */
    List<FinishedRun> latestFinished(TenantId tenantId, UUID integrationId, OperationKind kind, int limit);

    /** Operations created since {@code from} (a bucket boundary), counted per {@code bucket}-wide bin and status. */
    List<BucketCount> countByBucket(Optional<TenantId> tenantId, Instant from, Duration bucket);

    /**
     * Compare-and-set: writes {@code next} only if the row still matches {@code expected} (same status,
     * lock holder and attempts). A worker whose lease was rescued and re-claimed gets false and its late
     * result is discarded. Always releases the lease.
     */
    boolean update(Operation expected, Operation next);

    void insertStep(OperationStep step);

    List<OperationStep> findSteps(TenantId tenantId, UUID operationId);
}
