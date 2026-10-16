package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.integration.IntegrationPolicy;
import com.mindtechpy.nexus.domain.integration.IntegrationState;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.*;

@Repository
class JdbcIntegrationRepository implements IntegrationRepository {

    private static final String CATALOG_TARGET_INDEX = "uq_integrations_catalog_enabled_target";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<Integration> row;

    JdbcIntegrationRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.row = (rs, n) -> new Integration(uuid(rs, "id"), new TenantId(uuid(rs, "tenant_id")),
                Flow.valueOf(rs.getString("flow")), rs.getString("code"), uuid(rs, "source_connection_id"),
                uuid(rs, "target_connection_id"), IntegrationState.valueOf(rs.getString("state")),
                json.readValue(rs.getString("policy"), IntegrationPolicy.class), rs.getInt("version"),
                instant(rs, "updated_at"));
    }

    @Override
    public void insert(Integration integration) {
        try {
            insertRow(integration);
        } catch (DuplicateKeyException e) {
            throw translated(e);
        }
    }

    private void insertRow(Integration integration) {
        jdbc.sql("""
                        INSERT INTO integrations (id, tenant_id, flow, code, source_connection_id, target_connection_id,
                                                  state, policy, version, updated_at)
                        VALUES (:id, :tenantId, :flow, :code, :source, :target, :state, CAST(:policy AS jsonb), :version,
                                :updatedAt)""")
                .param("id", integration.id())
                .param("tenantId", integration.tenantId().value())
                .param("flow", integration.flow().name())
                .param("code", integration.code())
                .param("source", integration.sourceConnectionId())
                .param("target", integration.targetConnectionId())
                .param("state", integration.state().name())
                .param("policy", json.writeValueAsString(integration.policy()))
                .param("version", integration.version())
                .param("updatedAt", timestamp(integration.updatedAt()))
                .update();
    }

    @Override
    public void update(Integration integration) {
        try {
            updateRow(integration);
        } catch (DuplicateKeyException e) {
            throw translated(e);
        }
    }

    private void updateRow(Integration integration) {
        jdbc.sql("""
                        UPDATE integrations SET code = :code, source_connection_id = :source, target_connection_id = :target,
                               state = :state, policy = CAST(:policy AS jsonb), version = :version, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :id""")
                .param("tenantId", integration.tenantId().value())
                .param("id", integration.id())
                .param("code", integration.code())
                .param("source", integration.sourceConnectionId())
                .param("target", integration.targetConnectionId())
                .param("state", integration.state().name())
                .param("policy", json.writeValueAsString(integration.policy()))
                .param("version", integration.version())
                .param("updatedAt", timestamp(integration.updatedAt()))
                .update();
    }

    /** A concurrent writer won the race the application check could not see; answer as that check does. */
    private static RuntimeException translated(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains(CATALOG_TARGET_INDEX)) {
            return DomainException.conflict("catalog-target-in-use",
                    "Ya hay una integración CATALOG habilitada hacia esa conexión GiftHub");
        }
        if (message.contains("uq_integrations_tenant_code")) {
            return DomainException.conflict("integration-code-taken", "Ya existe una integración con ese código");
        }
        return e;
    }

    @Override
    public boolean existsByCode(TenantId tenantId, String code, UUID excludingId) {
        return jdbc.sql("SELECT count(*) FROM integrations WHERE tenant_id = :tenantId AND code = :code AND id <> :id")
                .param("tenantId", tenantId.value())
                .param("code", code)
                .param("id", excludingId)
                .query(Long.class).single() > 0;
    }

    @Override
    public Optional<Integration> findById(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM integrations WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId.value()).param("id", id).query(row).optional();
    }

    @Override
    public List<Integration> findAll(TenantId tenantId) {
        return jdbc.sql("SELECT * FROM integrations WHERE tenant_id = :tenantId ORDER BY flow, code")
                .param("tenantId", tenantId.value()).query(row).list();
    }

    @Override
    public List<Integration> findAllByFlow(Flow flow) {
        return jdbc.sql("SELECT * FROM integrations WHERE flow = :flow").param("flow", flow.name()).query(row).list();
    }
}
