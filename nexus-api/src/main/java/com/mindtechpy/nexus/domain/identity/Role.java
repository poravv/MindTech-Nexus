package com.mindtechpy.nexus.domain.identity;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static com.mindtechpy.nexus.domain.identity.Permission.*;

/** Console roles. {@code value} is the wire/database spelling. */
public enum Role {
    PLATFORM_ADMIN("platform_admin", EnumSet.allOf(Permission.class)),
    TENANT_ADMIN("tenant_admin", EnumSet.of(MANAGE_USERS, MANAGE_API_CLIENTS, MANAGE_CONNECTIONS, READ_TENANT_DATA, READ_AUDIT,
            MANAGE_INTEGRATIONS, CHANGE_INTEGRATION_STATE, REPLAY_OPERATIONS)),
    OPERATOR("operator", EnumSet.of(READ_TENANT_DATA, CHANGE_INTEGRATION_STATE, REPLAY_OPERATIONS)),
    AUDITOR("auditor", EnumSet.of(READ_TENANT_DATA, READ_AUDIT)),
    VIEWER("viewer", EnumSet.of(READ_TENANT_DATA));

    private final String value;
    private final Set<Permission> permissions;

    Role(String value, Set<Permission> permissions) {
        this.value = value;
        this.permissions = permissions;
    }

    public String value() {
        return value;
    }

    public boolean permits(Permission permission) {
        return permissions.contains(permission);
    }

    public static Role fromValue(String value) {
        return Arrays.stream(values())
                .filter(role -> role.value.equals(value))
                .findFirst()
                .orElseThrow(() -> DomainException.invalid("invalid-role", "Rol desconocido: " + value));
    }
}
