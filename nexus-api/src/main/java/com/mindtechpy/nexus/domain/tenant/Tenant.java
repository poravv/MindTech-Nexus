package com.mindtechpy.nexus.domain.tenant;

import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** A customer organization: one POS organization, one SIFEN issuer and one GiftHub tenant. */
public record Tenant(TenantId id, String slug, String name, TenantStatus status, Instant createdAt) {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

    public static Tenant create(String slug, String name, Instant now) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw DomainException.invalid("invalid-slug", "El slug debe ser minúsculas, dígitos o guiones (2-63)");
        }
        if (name == null || name.isBlank()) {
            throw DomainException.invalid("invalid-name", "El nombre es obligatorio");
        }
        return new Tenant(new TenantId(UUID.randomUUID()), slug, name.strip(), TenantStatus.ACTIVE, now);
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
