package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;
import java.util.UUID;

/** Read side of integrations. An integration of another tenant is reported as not found (ADR-005). */
public class IntegrationQueries {

    private final IntegrationRepository integrations;

    public IntegrationQueries(IntegrationRepository integrations) {
        this.integrations = integrations;
    }

    public List<Integration> list(Actor actor, TenantId requestedTenant) {
        actor.require(Permission.READ_TENANT_DATA);
        return integrations.findAll(actor.targetTenant(requestedTenant));
    }

    public Integration get(Actor actor, TenantId requestedTenant, UUID id) {
        actor.require(Permission.READ_TENANT_DATA);
        return integrations.findById(actor.targetTenant(requestedTenant), id)
                .orElseThrow(() -> DomainException.notFound("Integración"));
    }
}
