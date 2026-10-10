package com.mindtechpy.nexus.domain.identity;

import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A machine client (client_credentials). After a rotation the previous secret keeps working for
 * {@link #ROTATION_GRACE} so the caller can roll out the new one without downtime.
 */
public record ApiClient(UUID id, TenantId tenantId, String clientId, String name, PasswordHash secretHash,
                        PasswordHash previousSecretHash, Instant secretRotatedAt, Set<Scope> scopes,
                        Status status, Instant lastUsedAt) {

    public enum Status { ACTIVE, REVOKED }

    public static final Duration ROTATION_GRACE = Duration.ofHours(24);

    public static ApiClient create(TenantId tenantId, String clientId, String name, PasswordHash secretHash, Set<Scope> scopes) {
        if (name == null || name.isBlank()) {
            throw DomainException.invalid("invalid-name", "El nombre es obligatorio");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw DomainException.invalid("invalid-scope", "Se requiere al menos un scope");
        }
        return new ApiClient(UUID.randomUUID(), tenantId, clientId, name.strip(), secretHash, null, null,
                Set.copyOf(scopes), Status.ACTIVE, null);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean previousSecretStillValid(Instant now) {
        return previousSecretHash != null && secretRotatedAt != null
                && secretRotatedAt.plus(ROTATION_GRACE).isAfter(now);
    }

    public ApiClient rotate(PasswordHash newSecretHash, Instant now) {
        requireActive();
        return new ApiClient(id, tenantId, clientId, name, newSecretHash, secretHash, now, scopes, status, lastUsedAt);
    }

    public ApiClient revoke() {
        return new ApiClient(id, tenantId, clientId, name, secretHash, null, secretRotatedAt, scopes, Status.REVOKED, lastUsedAt);
    }

    private void requireActive() {
        if (!isActive()) {
            throw DomainException.conflict("client-revoked", "El cliente está revocado");
        }
    }
}
