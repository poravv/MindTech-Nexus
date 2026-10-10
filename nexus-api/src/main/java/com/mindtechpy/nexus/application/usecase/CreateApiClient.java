package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ApiClientRepository;
import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.application.port.out.TenantRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.ApiClient;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Registers a machine client for a tenant. The plain secret is only returned here. */
public class CreateApiClient {

    private final ApiClientRepository clients;
    private final TenantRepository tenants;
    private final PasswordHasherPort hasher;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public CreateApiClient(ApiClientRepository clients, TenantRepository tenants, PasswordHasherPort hasher,
                           AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        this.clients = clients;
        this.tenants = tenants;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public IssuedSecret execute(Actor actor, TenantId requestedTenant, String name, Set<String> scopeValues) {
        actor.require(Permission.MANAGE_API_CLIENTS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        if (tenants.findById(tenantId).isEmpty()) {
            throw DomainException.notFound("Tenant");
        }
        Set<Scope> scopes = scopeValues == null ? Set.of()
                : scopeValues.stream().map(Scope::fromValue).collect(Collectors.toUnmodifiableSet());
        String secret = OpaqueTokens.generate(32);
        ApiClient client = ApiClient.create(tenantId, "nxc_" + OpaqueTokens.hex(12), name, hasher.hash(secret), scopes);
        tx.inTransaction(() -> {
            clients.insert(client);
            audit.append(AuditEvent.of(actor, tenantId, "api-client.created", "api_client", client.id().toString(), null,
                    Map.of("clientId", client.clientId(), "name", client.name(), "scopes", client.scopes().stream().map(Scope::value).sorted().toList()),
                    clock.now()));
            return client;
        });
        return new IssuedSecret(client, secret);
    }
}
