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
     * Records that a run started at {@code runStartedAt} saw these internal ids at the source and clears a miss
     * recorded by an earlier run; ids without a mapping are ignored. The marks never go back in time, so an older
     * run finishing late cannot hide what a newer one saw or missed.
     */
    void markSeen(TenantId tenantId, UUID connectionId, EntityType type, Collection<String> internalIds, Instant runStartedAt);

    /**
     * A complete run started at {@code runStartedAt} did not see the mapped ids it has not marked seen: records
     * the miss on those without one. Idempotent.
     */
    void markMissed(TenantId tenantId, UUID connectionId, EntityType type, Instant runStartedAt);

    /**
     * Internal ids missed by the complete run started at {@code runStartedAt} and already missed by an earlier
     * complete run, with no sighting in between: two complete runs in a row did not see them.
     */
    List<String> findMissedTwice(TenantId tenantId, UUID connectionId, EntityType type, Instant runStartedAt);

    void deleteAll(TenantId tenantId, UUID connectionId, EntityType type, Collection<String> internalIds);
}
