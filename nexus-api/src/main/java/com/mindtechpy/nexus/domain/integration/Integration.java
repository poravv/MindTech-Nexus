package com.mindtechpy.nexus.domain.integration;

import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A tenant's configured flow between two connections. Operations always belong to one integration,
 * whose state and policy gate every step. {@code version} counts configuration changes.
 */
public record Integration(UUID id, TenantId tenantId, Flow flow, String code, UUID sourceConnectionId,
                          UUID targetConnectionId, IntegrationState state, IntegrationPolicy policy, int version,
                          Instant updatedAt) {

    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

    public Integration {
        if (flow == null) {
            throw DomainException.invalid("invalid-flow", "El flujo es obligatorio");
        }
        if (code == null || !CODE.matcher(code).matches()) {
            throw DomainException.invalid("invalid-code", "El código debe ser kebab-case en minúsculas (2-63)");
        }
        if (targetConnectionId == null) {
            throw DomainException.invalid("target-connection-required", "La conexión destino es obligatoria");
        }
        policy = (policy == null ? IntegrationPolicy.defaults() : policy).withDefaultReadTimeout(flow);
    }

    public static Integration create(TenantId tenantId, Flow flow, String code, UUID sourceConnectionId,
                                     UUID targetConnectionId, IntegrationPolicy policy, Instant now) {
        return new Integration(UUID.randomUUID(), tenantId, flow, code, sourceConnectionId, targetConnectionId,
                IntegrationState.ENABLED, policy, 1, now);
    }

    public Integration reconfigured(String code, UUID sourceConnectionId, UUID targetConnectionId,
                                    IntegrationPolicy policy, Instant now) {
        return new Integration(id, tenantId, flow, code, sourceConnectionId, targetConnectionId, state, policy,
                version + 1, now);
    }

    /** Leaving or entering {@code EMERGENCY_STOP} is reserved to whoever holds that permission (platform_admin). */
    public Integration changeState(Actor actor, IntegrationState target, Instant now) {
        actor.require(target.requiredToEnter());
        if (state == IntegrationState.EMERGENCY_STOP) {
            actor.require(Permission.EMERGENCY_STOP);
        }
        if (target == state) {
            throw DomainException.conflict("integration-state-unchanged", "La integración ya está en " + state);
        }
        return new Integration(id, tenantId, flow, code, sourceConnectionId, targetConnectionId, target, policy,
                version + 1, now);
    }
}
