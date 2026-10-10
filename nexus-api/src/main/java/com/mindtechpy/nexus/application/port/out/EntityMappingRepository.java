package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.mapping.EntityType;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ids of the same record in Nexus' terms ({@code internalId}) and in a connected system ({@code externalId}). */
public interface EntityMappingRepository {

    /** Inserts or updates the external id of each internal id, for one connection. */
    void saveAll(TenantId tenantId, UUID connectionId, EntityType type, Map<String, String> externalByInternal);

    /**
     * Records that a run started at {@code runStartedAt} saw these internal ids at the source; ids without a
     * mapping are ignored. The mark never goes back in time, so an older run finishing late cannot hide what a
     * newer one saw.
     */
    void markSeen(TenantId tenantId, UUID connectionId, EntityType type, Collection<String> internalIds, Instant runStartedAt);

    /** Internal ids mapped for the connection that no run started at or after {@code runStartedAt} has seen. */
    List<String> findNotSeenSince(TenantId tenantId, UUID connectionId, EntityType type, Instant runStartedAt);

    void deleteAll(TenantId tenantId, UUID connectionId, EntityType type, Collection<String> internalIds);
}
