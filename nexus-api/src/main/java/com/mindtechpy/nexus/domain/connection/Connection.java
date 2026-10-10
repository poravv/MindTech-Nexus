package com.mindtechpy.nexus.domain.connection;

import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A tenant's account on an external system. {@code sealedCredentials} is the AES-GCM ciphertext;
 * plain credentials never leave the application layer.
 */
public record Connection(UUID id, TenantId tenantId, ConnectorType connector, String name, String baseUrl,
                         byte[] sealedCredentials, Map<String, Object> settings,
                         Instant lastTestAt, Boolean lastTestOk) {

    public Connection {
        if (name == null || name.isBlank()) {
            throw DomainException.invalid("invalid-name", "El nombre es obligatorio");
        }
        name = name.strip();
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    /** Additional authenticated data binding the ciphertext to this exact row (ADR-006). */
    public static String credentialsAad(TenantId tenantId, UUID connectionId) {
        return tenantId + ":" + connectionId;
    }

    public String credentialsAad() {
        return credentialsAad(tenantId, id);
    }
}
