package com.mindtechpy.nexus.domain.identity;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.Arrays;

/** OAuth scopes a machine client can hold (spec §5.1). */
public enum Scope {
    FISCAL_ISSUE("fiscal:issue"),
    FISCAL_READ("fiscal:read"),
    ORDERS_WRITE("orders:write"),
    ORDERS_READ("orders:read"),
    CATALOG_READ("catalog:read"),
    OPERATIONS_READ("operations:read");

    private final String value;

    Scope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Scope fromValue(String value) {
        return Arrays.stream(values())
                .filter(scope -> scope.value.equals(value))
                .findFirst()
                .orElseThrow(() -> DomainException.invalid("invalid-scope", "Scope desconocido: " + value));
    }
}
