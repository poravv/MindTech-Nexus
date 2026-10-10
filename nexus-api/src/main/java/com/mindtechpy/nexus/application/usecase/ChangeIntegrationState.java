package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.integration.IntegrationState;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.Map;
import java.util.UUID;

/**
 * Switches an integration state with a mandatory reason, audited. Takes effect on the next step the
 * worker runs: leaving PAUSED or EMERGENCY_STOP resumes queued operations without replay.
 */
public class ChangeIntegrationState {

    private final IntegrationRepository integrations;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public ChangeIntegrationState(IntegrationRepository integrations, AuditRepository audit, ClockPort clock,
                                  TransactionRunner tx) {
        this.integrations = integrations;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public Integration execute(Actor actor, TenantId requestedTenant, UUID id, IntegrationState target, String reason) {
        TenantId tenantId = actor.targetTenant(requestedTenant);
        if (reason == null || reason.isBlank()) {
            throw DomainException.invalid("reason-required", "El motivo es obligatorio");
        }
        return tx.inTransaction(() -> {
            Integration current = integrations.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Integración"));
            Integration changed = current.changeState(actor, target, clock.now());
            integrations.update(changed);
            audit.append(AuditEvent.of(actor, tenantId, "integration.state-changed", "integration", id.toString(),
                    Map.of("state", current.state().name()),
                    Map.of("state", changed.state().name(), "reason", reason.strip()), clock.now()));
            return changed;
        });
    }
}
