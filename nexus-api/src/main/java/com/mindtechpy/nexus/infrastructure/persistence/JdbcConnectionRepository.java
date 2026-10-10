package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.*;

@Repository
class JdbcConnectionRepository implements ConnectionRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<Connection> row;

    JdbcConnectionRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.row = (rs, n) -> new Connection(uuid(rs, "id"), new TenantId(uuid(rs, "tenant_id")),
                ConnectorType.valueOf(rs.getString("connector")), rs.getString("name"), rs.getString("base_url"),
                rs.getBytes("credentials_enc"), map(json, rs.getString("settings")),
                instant(rs, "last_test_at"), (Boolean) rs.getObject("last_test_ok"));
    }

    @Override
    public void insert(Connection connection) {
        jdbc.sql("""
                        INSERT INTO connections (id, tenant_id, connector, name, base_url, credentials_enc, settings)
                        VALUES (:id, :tenantId, :connector, :name, :baseUrl, :credentials, CAST(:settings AS jsonb))""")
                .param("id", connection.id())
                .param("tenantId", connection.tenantId().value())
                .param("connector", connection.connector().name())
                .param("name", connection.name())
                .param("baseUrl", connection.baseUrl())
                .param("credentials", connection.sealedCredentials())
                .param("settings", json(json, connection.settings()))
                .update();
    }

    @Override
    public void update(Connection connection) {
        jdbc.sql("""
                        UPDATE connections SET name = :name, base_url = :baseUrl, credentials_enc = :credentials,
                               settings = CAST(:settings AS jsonb), updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id""")
                .param("tenantId", connection.tenantId().value())
                .param("id", connection.id())
                .param("name", connection.name())
                .param("baseUrl", connection.baseUrl())
                .param("credentials", connection.sealedCredentials())
                .param("settings", json(json, connection.settings()))
                .update();
    }

    @Override
    public boolean existsByName(TenantId tenantId, ConnectorType connector, String name, UUID excludingId) {
        return jdbc.sql("""
                        SELECT count(*) FROM connections
                        WHERE tenant_id = :tenantId AND connector = :connector AND name = :name AND id <> :id""")
                .param("tenantId", tenantId.value())
                .param("connector", connector.name())
                .param("name", name)
                .param("id", excludingId)
                .query(Long.class).single() > 0;
    }

    @Override
    public Optional<Connection> findById(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM connections WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId.value()).param("id", id).query(row).optional();
    }

    @Override
    public Optional<Connection> findForWebhook(UUID id) {
        return jdbc.sql("SELECT * FROM connections WHERE id = :id").param("id", id).query(row).optional();
    }

    @Override
    public List<Connection> findAll(TenantId tenantId) {
        return jdbc.sql("SELECT * FROM connections WHERE tenant_id = :tenantId ORDER BY connector, name")
                .param("tenantId", tenantId.value()).query(row).list();
    }

    @Override
    public void recordTest(TenantId tenantId, UUID id, Instant testedAt, boolean ok) {
        jdbc.sql("UPDATE connections SET last_test_at = :testedAt, last_test_ok = :ok, updated_at = now() WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId.value())
                .param("id", id)
                .param("testedAt", timestamp(testedAt))
                .param("ok", ok)
                .update();
    }
}
