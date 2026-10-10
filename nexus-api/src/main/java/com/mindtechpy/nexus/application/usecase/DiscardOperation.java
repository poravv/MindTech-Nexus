package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Takes a DEAD operation out of the DLQ without replaying it: {@code DISCARDED} is terminal, so the
 * integration stops counting it as degraded. Same permission as replay; always audited.
 */
public class DiscardOperation {

    private final OperationRepository operations;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public DiscardOperation(OperationRepository operations, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        this.operations = operations;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    /** 404 for an operation of another tenant, 409 {@code operation-not-dead} unless it is DEAD. */
    public Operation execute(Actor actor, TenantId requestedTenant, UUID id) {
        actor.require(Permission.REPLAY_OPERATIONS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        return tx.inTransaction(() -> {
            Operation dead = operations.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Operación"));
            Operation discarded = dead.discarded(clock.now());
            if (!operations.update(dead, discarded)) {
                throw DomainException.conflict("operation-changed", "La operación cambió mientras se descartaba");
            }
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", dead.status().name());
            before.put("step", dead.currentStep());
            before.put("deadReason", dead.deadReason());
            audit.append(AuditEvent.of(actor, tenantId, "operation.discarded", "operation", id.toString(), before,
                    Map.of("status", discarded.status().name()), clock.now()));
            return discarded;
        });
    }
}
