package com.mindtechpy.nexus.domain.identity;

import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.Optional;

/**
 * Who is performing an action. The tenant always comes from the authenticated token; a tenant
 * requested in the URL is only honoured for {@code platform_admin} (ADR-005).
 */
public record Actor(Type type, String id, Role role, TenantId tenantId) {

    public enum Type { USER, CLIENT, SYSTEM }

    public static Actor system() {
        return new Actor(Type.SYSTEM, "system", null, null);
    }

    public static Actor user(String userId, Role role, TenantId tenantId) {
        return new Actor(Type.USER, userId, role, tenantId);
    }

    public boolean isPlatformAdmin() {
        return role == Role.PLATFORM_ADMIN;
    }

    public void require(Permission permission) {
        if (role == null || !role.permits(permission)) {
            throw DomainException.forbidden("forbidden", "Permiso insuficiente");
        }
    }

    /** Tenant a tenant-scoped action applies to. Platform admins must name it explicitly. */
    public TenantId targetTenant(TenantId requested) {
        if (isPlatformAdmin()) {
            if (requested == null) {
                throw DomainException.invalid("tenant-required", "platform_admin debe indicar tenantId");
            }
            return requested;
        }
        return ownTenant(requested);
    }

    /** Tenant filter for listings: platform admins may see every tenant at once. */
    public Optional<TenantId> tenantFilter(TenantId requested) {
        if (isPlatformAdmin()) {
            return Optional.ofNullable(requested);
        }
        return Optional.of(ownTenant(requested));
    }

    private TenantId ownTenant(TenantId requested) {
        if (requested != null && !requested.equals(tenantId)) {
            throw DomainException.forbidden("tenant-mismatch", "El tenant solicitado no coincide con el del token");
        }
        return tenantId;
    }
}
