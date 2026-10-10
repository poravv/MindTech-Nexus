package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.EntityMappingRepository;
import com.mindtechpy.nexus.domain.mapping.EntityType;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
class JdbcEntityMappingRepository implements EntityMappingRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcEntityMappingRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One statement per call (a catalog page is up to 500 rows): the pairs travel as one JSON object. */
    @Override
    public void saveAll(TenantId tenantId, UUID connectionId, EntityType type, Map<String, String> externalByInternal) {
        if (externalByInternal.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO entity_mappings (id, tenant_id, connection_id, entity_type, internal_id, external_id)
                        SELECT gen_random_uuid(), :tenantId, :connectionId, :type, m.internal_id, m.external_id
                        FROM jsonb_each_text(CAST(:pairs AS jsonb)) AS m(internal_id, external_id)
                        ON CONFLICT (tenant_id, connection_id, entity_type, internal_id)
                        DO UPDATE SET external_id = EXCLUDED.external_id, updated_at = now()
                        WHERE entity_mappings.external_id <> EXCLUDED.external_id""")
                .param("tenantId", tenantId.value())
                .param("connectionId", connectionId)
                .param("type", type.name())
                .param("pairs", json.writeValueAsString(externalByInternal))
                .update();
    }

    @Override
    public void markSeen(TenantId tenantId, UUID connectionId, EntityType type, Collection<String> internalIds, Instant runStartedAt) {
        if (internalIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        UPDATE entity_mappings SET last_seen_at = GREATEST(last_seen_at, :runStartedAt)
                        WHERE tenant_id = :tenantId AND connection_id = :connectionId AND entity_type = :type
                          AND internal_id = ANY (CAST(:ids AS text[]))""")
                .param("runStartedAt", JdbcValues.timestamp(runStartedAt))
                .param("tenantId", tenantId.value())
                .param("connectionId", connectionId)
                .param("type", type.name())
                .param("ids", internalIds.toArray(String[]::new))
                .update();
    }

    @Override
    public List<String> findNotSeenSince(TenantId tenantId, UUID connectionId, EntityType type, Instant runStartedAt) {
        return jdbc.sql("""
                        SELECT internal_id FROM entity_mappings
                        WHERE tenant_id = :tenantId AND connection_id = :connectionId AND entity_type = :type
                          AND (last_seen_at IS NULL OR last_seen_at < :runStartedAt)
                        ORDER BY internal_id""")
                .param("tenantId", tenantId.value())
                .param("connectionId", connectionId)
                .param("type", type.name())
                .param("runStartedAt", JdbcValues.timestamp(runStartedAt))
                .query(String.class).list();
    }

    @Override
    public void deleteAll(TenantId tenantId, UUID connectionId, EntityType type, Collection<String> internalIds) {
        if (internalIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        DELETE FROM entity_mappings
                        WHERE tenant_id = :tenantId AND connection_id = :connectionId AND entity_type = :type
                          AND internal_id = ANY (CAST(:ids AS text[]))""")
                .param("tenantId", tenantId.value())
                .param("connectionId", connectionId)
                .param("type", type.name())
                .param("ids", internalIds.toArray(String[]::new))
                .update();
    }
}
