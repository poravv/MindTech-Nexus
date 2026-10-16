package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStep;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.*;

@Repository
class JdbcOperationRepository implements OperationRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<Operation> row;
    private final RowMapper<OperationStep> stepRow;

    JdbcOperationRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.row = OperationRows.mapper(json);
        this.stepRow = (rs, n) -> new OperationStep(uuid(rs, "id"), new TenantId(uuid(rs, "tenant_id")),
                uuid(rs, "operation_id"), rs.getString("step"), rs.getInt("attempt"),
                OperationStep.Status.valueOf(rs.getString("status")), rs.getString("external_ref"),
                (Integer) rs.getObject("response_code"), rs.getString("error_code"), instant(rs, "started_at"),
                instant(rs, "finished_at"));
    }

    @Override
    public Optional<Operation> findByKey(TenantId tenantId, OperationKind kind, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM operations WHERE tenant_id = :tenantId AND kind = :kind AND idempotency_key = :key")
                .param("tenantId", tenantId.value())
                .param("kind", kind.name())
                .param("key", idempotencyKey)
                .query(row).optional();
    }

    @Override
    public boolean insertIfAbsent(Operation op) {
        return jdbc.sql("""
                        INSERT INTO operations (id, tenant_id, integration_id, flow, kind, idempotency_key, request_hash,
                                                status, attempts, max_attempts, next_run_at, correlation_id, payload,
                                                created_at, updated_at)
                        VALUES (:id, :tenantId, :integrationId, :flow, :kind, :key, :hash, :status, :attempts,
                                :maxAttempts, :nextRunAt, :correlationId, CAST(:payload AS jsonb), :createdAt, :createdAt)
                        ON CONFLICT (tenant_id, kind, idempotency_key) DO NOTHING""")
                .param("id", op.id())
                .param("tenantId", op.tenantId().value())
                .param("integrationId", op.integrationId())
                .param("flow", op.flow().name())
                .param("kind", op.kind().name())
                .param("key", op.idempotencyKey())
                .param("hash", op.requestHash())
                .param("status", op.status().name())
                .param("attempts", op.attempts())
                .param("maxAttempts", op.maxAttempts())
                .param("nextRunAt", timestamp(op.nextRunAt()))
                .param("correlationId", op.correlationId())
                .param("payload", json(json, op.payload()))
                .param("createdAt", timestamp(op.createdAt()))
                .update() == 1;
    }

    @Override
    public Optional<Operation> findById(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM operations WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId.value()).param("id", id).query(row).optional();
    }

    @Override
    public List<Operation> find(Optional<TenantId> tenantId, Filter filter, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM operations WHERE true");
        Map<String, Object> params = new HashMap<>();
        tenantId.ifPresent(id -> {
            sql.append(" AND tenant_id = :tenantId");
            params.put("tenantId", id.value());
        });
        if (filter.integrationId() != null) {
            sql.append(" AND integration_id = :integrationId");
            params.put("integrationId", filter.integrationId());
        }
        if (filter.flow() != null) {
            sql.append(" AND flow = :flow");
            params.put("flow", filter.flow().name());
        }
        if (filter.status() != null) {
            sql.append(" AND status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.from() != null) {
            sql.append(" AND created_at >= :from");
            params.put("from", timestamp(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND created_at < :to");
            params.put("to", timestamp(filter.to()));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            sql.append(" AND (id::text = :q OR idempotency_key = :q OR correlation_id = :q)");
            params.put("q", filter.q().strip());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(sql.toString()).params(params).query(row).list();
    }

    @Override
    public long countUnfinished(TenantId tenantId) {
        return jdbc.sql("SELECT count(*) FROM operations WHERE tenant_id = :tenantId AND status IN ('PENDING', 'RETRY', 'RUNNING')")
                .param("tenantId", tenantId.value())
                .query(Long.class).single();
    }

    @Override
    public boolean hasUnfinished(TenantId tenantId, OperationKind kind, UUID targetConnectionId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM operations o JOIN integrations i ON i.id = o.integration_id
                                       WHERE o.tenant_id = :tenantId AND o.kind = :kind AND i.target_connection_id = :connectionId
                                         AND o.status IN ('PENDING', 'RETRY', 'RUNNING'))""")
                .param("tenantId", tenantId.value())
                .param("kind", kind.name())
                .param("connectionId", targetConnectionId)
                .query(Boolean.class).single();
    }

    @Override
    public void lockCatalogTarget(UUID targetConnectionId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "catalog-sync:" + targetConnectionId)
                .query((rs, n) -> 1).single();
    }

    @Override
    public List<StatusFlowCount> countSince(Optional<TenantId> tenantId, Instant from) {
        String tenantFilter = tenantId.isPresent() ? " AND tenant_id = :tenantId" : "";
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT status, flow, count(*) AS total FROM operations"
                        + " WHERE created_at >= :from" + tenantFilter + " GROUP BY status, flow")
                .param("from", timestamp(from));
        tenantId.ifPresent(id -> statement.param("tenantId", id.value()));
        return statement.query((rs, n) -> new StatusFlowCount(OperationStatus.valueOf(rs.getString("status")),
                Flow.valueOf(rs.getString("flow")), rs.getLong("total"))).list();
    }

    @Override
    public long countDead(Optional<TenantId> tenantId) {
        String tenantFilter = tenantId.isPresent() ? " AND tenant_id = :tenantId" : "";
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT count(*) FROM operations WHERE status = 'DEAD'" + tenantFilter);
        tenantId.ifPresent(id -> statement.param("tenantId", id.value()));
        return statement.query(Long.class).single();
    }

    @Override
    public List<IntegrationStatusCount> countByIntegrationSince(TenantId tenantId, Instant from) {
        return jdbc.sql("""
                        SELECT integration_id, status, count(*) AS total, max(created_at) AS last_created_at FROM operations
                        WHERE tenant_id = :tenantId AND created_at >= :from GROUP BY integration_id, status""")
                .param("tenantId", tenantId.value())
                .param("from", timestamp(from))
                .query((rs, n) -> new IntegrationStatusCount(uuid(rs, "integration_id"), OperationStatus.valueOf(rs.getString("status")),
                        rs.getLong("total"), instant(rs, "last_created_at"))).list();
    }

    @Override
    public Map<UUID, Long> countDeadByIntegration(TenantId tenantId) {
        Map<UUID, Long> dead = new HashMap<>();
        jdbc.sql("SELECT integration_id, count(*) AS total FROM operations WHERE tenant_id = :tenantId AND status = 'DEAD' GROUP BY integration_id")
                .param("tenantId", tenantId.value())
                .query(rs -> {
                    dead.put(uuid(rs, "integration_id"), rs.getLong("total"));
                });
        return dead;
    }

    @Override
    public Map<UUID, LastFailure> lastFailuresSince(TenantId tenantId, Instant from) {
        Map<UUID, LastFailure> failures = new HashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT ON (integration_id) integration_id, id, status, current_step,
                               coalesce(dead_reason, last_error ->> 'code') AS error_code, coalesce(finished_at, updated_at) AS failed_at
                        FROM operations
                        WHERE tenant_id = :tenantId AND created_at >= :from AND status IN ('FAILED', 'DEAD')
                        ORDER BY integration_id, created_at DESC, id DESC""")
                .param("tenantId", tenantId.value())
                .param("from", timestamp(from))
                .query(rs -> {
                    failures.put(uuid(rs, "integration_id"), new LastFailure(uuid(rs, "id"), OperationStatus.valueOf(rs.getString("status")),
                            rs.getString("current_step"), rs.getString("error_code"), instant(rs, "failed_at")));
                });
        return failures;
    }

    @Override
    public List<BucketCount> countByBucket(Optional<TenantId> tenantId, Instant from, Duration bucket) {
        String tenantFilter = tenantId.isPresent() ? " AND tenant_id = :tenantId" : "";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        SELECT date_bin(make_interval(secs => :bucketSeconds), created_at, TIMESTAMPTZ 'epoch') AS bucket, status,
                               count(*) AS total
                        FROM operations WHERE created_at >= :from""" + tenantFilter + " GROUP BY bucket, status")
                .param("bucketSeconds", bucket.toSeconds())
                .param("from", timestamp(from));
        tenantId.ifPresent(id -> statement.param("tenantId", id.value()));
        return statement.query((rs, n) -> new BucketCount(instant(rs, "bucket"), OperationStatus.valueOf(rs.getString("status")),
                rs.getLong("total"))).list();
    }

    @Override
    public List<FinishedRun> latestFinished(TenantId tenantId, UUID integrationId, OperationKind kind, int limit) {
        return jdbc.sql("""
                        SELECT status, finished_at FROM operations
                        WHERE integration_id = :integrationId AND tenant_id = :tenantId AND kind = :kind AND finished_at IS NOT NULL
                        ORDER BY finished_at DESC LIMIT :limit""")
                .param("integrationId", integrationId)
                .param("tenantId", tenantId.value())
                .param("kind", kind.name())
                .param("limit", limit)
                .query((rs, n) -> new FinishedRun(OperationStatus.valueOf(rs.getString("status")), instant(rs, "finished_at")))
                .list();
    }

    @Override
    public boolean update(Operation expected, Operation next) {
        return jdbc.sql("""
                        UPDATE operations SET status = :status, current_step = :step, attempts = :attempts,
                               next_run_at = :nextRunAt, result = CAST(:result AS jsonb), last_error = CAST(:lastError AS jsonb),
                               dead_reason = :deadReason, finished_at = :finishedAt, locked_by = NULL, locked_until = NULL,
                               updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id AND status = :expectedStatus
                          AND attempts = :expectedAttempts AND locked_by IS NOT DISTINCT FROM CAST(:expectedLockedBy AS text)""")
                .param("tenantId", expected.tenantId().value())
                .param("id", expected.id())
                .param("expectedStatus", expected.status().name())
                .param("expectedAttempts", expected.attempts())
                .param("expectedLockedBy", expected.lockedBy())
                .param("status", next.status().name())
                .param("step", next.currentStep())
                .param("attempts", next.attempts())
                .param("nextRunAt", timestamp(next.nextRunAt()))
                .param("result", json(json, next.result()))
                .param("lastError", json(json, OperationRows.errorJson(next.lastError())))
                .param("deadReason", next.deadReason())
                .param("finishedAt", timestamp(next.finishedAt()))
                .update() == 1;
    }

    @Override
    public void insertStep(OperationStep step) {
        jdbc.sql("""
                        INSERT INTO operation_steps (id, tenant_id, operation_id, step, attempt, status, external_ref,
                                                     response_code, error_code, started_at, finished_at)
                        VALUES (:id, :tenantId, :operationId, :step, :attempt, :status, :externalRef, :responseCode,
                                :errorCode, :startedAt, :finishedAt)""")
                .param("id", step.id())
                .param("tenantId", step.tenantId().value())
                .param("operationId", step.operationId())
                .param("step", step.step())
                .param("attempt", step.attempt())
                .param("status", step.status().name())
                .param("externalRef", step.externalRef())
                .param("responseCode", step.responseCode())
                .param("errorCode", step.errorCode())
                .param("startedAt", timestamp(step.startedAt()))
                .param("finishedAt", timestamp(step.finishedAt()))
                .update();
    }

    @Override
    public List<OperationStep> findSteps(TenantId tenantId, UUID operationId) {
        return jdbc.sql("SELECT * FROM operation_steps WHERE tenant_id = :tenantId AND operation_id = :operationId ORDER BY started_at, id")
                .param("tenantId", tenantId.value())
                .param("operationId", operationId)
                .query(stepRow).list();
    }
}
