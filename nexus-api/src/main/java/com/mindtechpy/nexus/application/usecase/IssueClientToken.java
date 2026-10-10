package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ApiClientRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.application.port.out.TenantRepository;
import com.mindtechpy.nexus.domain.identity.ApiClient;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.domain.tenant.Tenant;

import java.time.Instant;
import java.util.Set;

/**
 * client_credentials grant: authenticates the client and returns what goes into the access token.
 * Every failure is the same {@code invalid-client} so callers cannot probe which part was wrong.
 */
public class IssueClientToken {

    public record ClientGrant(String clientId, TenantId tenantId, Set<Scope> scopes) {
    }

    private final ApiClientRepository clients;
    private final TenantRepository tenants;
    private final PasswordHasherPort hasher;
    private final ClockPort clock;

    public IssueClientToken(ApiClientRepository clients, TenantRepository tenants, PasswordHasherPort hasher, ClockPort clock) {
        this.clients = clients;
        this.tenants = tenants;
        this.hasher = hasher;
        this.clock = clock;
    }

    public ClientGrant execute(String clientId, String secret) {
        Instant now = clock.now();
        ApiClient client = clients.findByClientId(clientId == null ? "" : clientId)
                .filter(ApiClient::isActive)
                .filter(c -> tenants.findById(c.tenantId()).map(Tenant::isActive).orElse(false))
                .filter(c -> secretMatches(c, secret, now))
                .orElseThrow(() -> DomainException.unauthorized("invalid-client", "Credenciales de cliente inválidas"));
        clients.touchLastUsed(client.id(), now);
        return new ClientGrant(client.clientId(), client.tenantId(), client.scopes());
    }

    private boolean secretMatches(ApiClient client, String secret, Instant now) {
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        return hasher.matches(secret, client.secretHash())
                || (client.previousSecretStillValid(now) && hasher.matches(secret, client.previousSecretHash()));
    }
}
