package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.mapping.EntityType;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.Map;
import java.util.UUID;

/** Ids of the same record in Nexus' terms ({@code internalId}) and in a connected system ({@code externalId}). */
public interface EntityMappingRepository {

    /** Inserts or updates the external id of each internal id, for one connection. */
    void saveAll(TenantId tenantId, UUID connectionId, EntityType type, Map<String, String> externalByInternal);
}
