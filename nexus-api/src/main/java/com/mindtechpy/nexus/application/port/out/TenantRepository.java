package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.domain.tenant.Tenant;

import java.util.List;
import java.util.Optional;

public interface TenantRepository {

    void insert(Tenant tenant);

    boolean existsBySlug(String slug);

    Optional<Tenant> findById(TenantId id);

    List<Tenant> findAll();
}
