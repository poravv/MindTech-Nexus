package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.identity.ApiClient;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiClientRepository {

    void insert(ApiClient client);

    void update(TenantId tenantId, ApiClient client);

    /** Global lookup used only by the token endpoint; the tenant comes from the stored row. */
    Optional<ApiClient> findByClientId(String clientId);

    Optional<ApiClient> findById(TenantId tenantId, UUID id);

    List<ApiClient> findAll(TenantId tenantId);

    void touchLastUsed(UUID id, Instant now);
}
