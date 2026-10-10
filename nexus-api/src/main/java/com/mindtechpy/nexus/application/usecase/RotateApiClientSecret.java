package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ApiClientRepository;
import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.ApiClient;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.Map;
import java.util.UUID;

/** Issues a new secret; the previous one stays valid for {@link ApiClient#ROTATION_GRACE}. */
public class RotateApiClientSecret {

    private final ApiClientRepository clients;
    private final PasswordHasherPort hasher;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public RotateApiClientSecret(ApiClientRepository clients, PasswordHasherPort hasher, AuditRepository audit,
                                 ClockPort clock, TransactionRunner tx) {
        this.clients = clients;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public IssuedSecret execute(Actor actor, TenantId requestedTenant, UUID id) {
        actor.require(Permission.MANAGE_API_CLIENTS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        ApiClient current = clients.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Cliente"));
        String secret = OpaqueTokens.generate(32);
        ApiClient rotated = current.rotate(hasher.hash(secret), clock.now());
        tx.inTransaction(() -> {
            clients.update(tenantId, rotated);
            audit.append(AuditEvent.of(actor, tenantId, "api-client.secret-rotated", "api_client", id.toString(), null,
                    Map.of("clientId", rotated.clientId(), "previousValidUntil",
                            rotated.secretRotatedAt().plus(ApiClient.ROTATION_GRACE).toString()), clock.now()));
            return rotated;
        });
        return new IssuedSecret(rotated, secret);
    }
}
