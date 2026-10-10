package com.mindtechpy.nexus.domain.shared;

import java.util.Objects;
import java.util.UUID;

/** Identity of a tenant. Every business record and query is scoped by it. */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "tenant id");
    }

    public static TenantId of(UUID value) {
        return value == null ? null : new TenantId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
