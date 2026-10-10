package com.mindtechpy.nexus.domain.identity;

/** Console actions guarded by role (spec §5.2). */
public enum Permission {
    MANAGE_TENANTS,
    MANAGE_USERS,
    MANAGE_API_CLIENTS,
    MANAGE_CONNECTIONS,
    READ_TENANT_DATA,
    READ_AUDIT
}
