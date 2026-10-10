package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationRepository {

    void insert(Integration integration);

    void update(Integration integration);

    boolean existsByCode(TenantId tenantId, String code, UUID excludingId);

    /** Always reads the current row (no cache): the worker gates every step on it. */
    Optional<Integration> findById(TenantId tenantId, UUID id);

    List<Integration> findAll(TenantId tenantId);

    /** Integrations of every tenant for one flow; only for system jobs (catalog scheduler). */
    List<Integration> findAllByFlow(Flow flow);
}
