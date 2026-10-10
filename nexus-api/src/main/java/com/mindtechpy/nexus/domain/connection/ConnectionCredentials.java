package com.mindtechpy.nexus.domain.connection;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.Map;

/**
 * Plain credentials of a connection (e.g. {@code accessKey}/{@code secretKey} for SIFEN). Only lives
 * in memory: at rest it is always sealed with AES-GCM, and {@link #toString()} never reveals values.
 */
public record ConnectionCredentials(Map<String, String> values) {

    public ConnectionCredentials {
        if (values == null || values.isEmpty() || values.values().stream().anyMatch(v -> v == null || v.isBlank())) {
            throw DomainException.invalid("invalid-credentials", "Las credenciales no pueden estar vacías");
        }
        values = Map.copyOf(values);
    }

    @Override
    public String toString() {
        return "ConnectionCredentials" + values.keySet() + "[***]";
    }
}
