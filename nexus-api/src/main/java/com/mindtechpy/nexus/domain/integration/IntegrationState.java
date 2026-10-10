package com.mindtechpy.nexus.domain.integration;

import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.DomainException;

/**
 * Operational switch of an integration (spec §7.3). The worker only dispatches {@code ENABLED} and
 * {@code DRAINING}; new operations are accepted only while {@code ENABLED} or {@code PAUSED}, and a
 * paused integration keeps them queued until it is enabled again.
 */
public enum IntegrationState {
    ENABLED(Permission.CHANGE_INTEGRATION_STATE),
    PAUSED(Permission.CHANGE_INTEGRATION_STATE),
    DRAINING(Permission.CHANGE_INTEGRATION_STATE),
    DISABLED(Permission.MANAGE_INTEGRATIONS),
    EMERGENCY_STOP(Permission.EMERGENCY_STOP);

    private final Permission requiredToEnter;

    IntegrationState(Permission requiredToEnter) {
        this.requiredToEnter = requiredToEnter;
    }

    public Permission requiredToEnter() {
        return requiredToEnter;
    }

    public boolean dispatches() {
        return this == ENABLED || this == DRAINING;
    }

    public void requireAcceptsNewOperations() {
        switch (this) {
            case ENABLED, PAUSED -> {
            }
            case DRAINING -> throw DomainException.conflict("integration-draining",
                    "La integración está drenando: no acepta operaciones nuevas");
            case DISABLED -> throw DomainException.conflict("integration-disabled", "La integración está deshabilitada");
            case EMERGENCY_STOP -> throw DomainException.conflict("integration-emergency-stop",
                    "La integración está en parada de emergencia");
        }
    }
}
