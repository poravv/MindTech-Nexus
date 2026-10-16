package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends a DEAD operation back to the queue (spec §7.1): same idempotency key, attempts reset, resumes
 * at the step that failed. Always audited.
 */
public class ReplayOperation {

    private final OperationRepository operations;
    private final IntegrationRepository integrations;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public ReplayOperation(OperationRepository operations, IntegrationRepository integrations, AuditRepository audit,
                           ClockPort clock, TransactionRunner tx) {
        this.operations = operations;
        this.integrations = integrations;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public Operation execute(Actor actor, TenantId requestedTenant, UUID id) {
        actor.require(Permission.REPLAY_OPERATIONS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        return tx.inTransaction(() -> {
            Operation dead = operations.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Operación"));
            Operation replayed = dead.replayed(clock.now());
            if (dead.kind() == OperationKind.CATALOG_SYNC) {
                requireCatalogTargetIdle(tenantId, dead);
            }
            if (!operations.update(dead, replayed)) {
                throw DomainException.conflict("operation-changed", "La operación cambió mientras se reprocesaba");
            }
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", dead.status().name());
            before.put("step", dead.currentStep());
            before.put("deadReason", dead.deadReason());
            audit.append(AuditEvent.of(actor, tenantId, "operation.replayed", "operation", id.toString(), before,
                    Map.of("status", replayed.status().name(), "idempotencyKey", replayed.idempotencyKey()), clock.now()));
            return replayed;
        });
    }

    /** Same lock as the triggers of {@link RunCatalogSync}: a replay is a new run to that GiftHub and must not overlap another. */
    private void requireCatalogTargetIdle(TenantId tenantId, Operation catalogRun) {
        UUID target = integrations.findById(tenantId, catalogRun.integrationId())
                .orElseThrow(() -> DomainException.notFound("Integración")).targetConnectionId();
        operations.lockCatalogTarget(target);
        if (operations.hasUnfinished(tenantId, OperationKind.CATALOG_SYNC, target)) {
            throw DomainException.conflict("catalog-run-in-progress",
                    "Ya hay una sincronización de catálogo en curso hacia este GiftHub; reintente cuando termine");
        }
    }
}
