package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ApiClientRepository;
import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.ApiClient;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.Map;
import java.util.UUID;

/** Revokes a client: new tokens are refused immediately (issued tokens expire within 15 min). */
public class RevokeApiClient {

    private final ApiClientRepository clients;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public RevokeApiClient(ApiClientRepository clients, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        this.clients = clients;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public ApiClient execute(Actor actor, TenantId requestedTenant, UUID id) {
        actor.require(Permission.MANAGE_API_CLIENTS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        ApiClient revoked = clients.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Cliente")).revoke();
        return tx.inTransaction(() -> {
            clients.update(tenantId, revoked);
            audit.append(AuditEvent.of(actor, tenantId, "api-client.revoked", "api_client", id.toString(), null,
                    Map.of("clientId", revoked.clientId()), clock.now()));
            return revoked;
        });
    }
}
