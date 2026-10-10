package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.EntityMappingRepository;
import com.mindtechpy.nexus.domain.mapping.EntityType;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

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
}
