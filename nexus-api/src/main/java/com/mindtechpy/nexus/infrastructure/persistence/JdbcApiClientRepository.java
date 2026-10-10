package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.ApiClientRepository;
import com.mindtechpy.nexus.domain.identity.ApiClient;
import com.mindtechpy.nexus.domain.identity.PasswordHash;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.*;

/** Scopes are stored as {@code text[]} and exchanged with the driver as a space-separated string. */
@Repository
class JdbcApiClientRepository implements ApiClientRepository {

    private static final String SELECT = "SELECT *, array_to_string(scopes, ' ') AS scope_list FROM api_clients";

    private static final RowMapper<ApiClient> ROW = (rs, n) -> {
        String previous = rs.getString("secret_previous_hash");
        return new ApiClient(uuid(rs, "id"), new TenantId(uuid(rs, "tenant_id")), rs.getString("client_id"),
                rs.getString("name"), new PasswordHash(rs.getString("secret_hash")),
                previous == null ? null : new PasswordHash(previous), instant(rs, "secret_rotated_at"),
                Arrays.stream(rs.getString("scope_list").split(" ")).map(Scope::fromValue).collect(Collectors.toUnmodifiableSet()),
                ApiClient.Status.valueOf(rs.getString("status")), instant(rs, "last_used_at"));
    };

    private final JdbcClient jdbc;

    JdbcApiClientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ApiClient client) {
        jdbc.sql("""
                        INSERT INTO api_clients (id, tenant_id, client_id, name, secret_hash, scopes, status)
                        VALUES (:id, :tenantId, :clientId, :name, :hash, string_to_array(:scopes, ' '), :status)""")
                .param("id", client.id())
                .param("tenantId", client.tenantId().value())
                .param("clientId", client.clientId())
                .param("name", client.name())
                .param("hash", client.secretHash().value())
                .param("scopes", client.scopes().stream().map(Scope::value).sorted().collect(Collectors.joining(" ")))
                .param("status", client.status().name())
                .update();
    }

    @Override
    public void update(TenantId tenantId, ApiClient client) {
        jdbc.sql("""
                        UPDATE api_clients SET secret_hash = :hash, secret_previous_hash = :previous,
                               secret_rotated_at = :rotatedAt, status = :status, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id""")
                .param("tenantId", tenantId.value())
                .param("id", client.id())
                .param("hash", client.secretHash().value())
                .param("previous", client.previousSecretHash() == null ? null : client.previousSecretHash().value())
                .param("rotatedAt", timestamp(client.secretRotatedAt()))
                .param("status", client.status().name())
                .update();
    }

    @Override
    public Optional<ApiClient> findByClientId(String clientId) {
        return jdbc.sql(SELECT + " WHERE client_id = :clientId").param("clientId", clientId).query(ROW).optional();
    }

    @Override
    public Optional<ApiClient> findById(TenantId tenantId, UUID id) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId.value()).param("id", id).query(ROW).optional();
    }

    @Override
    public List<ApiClient> findAll(TenantId tenantId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId ORDER BY created_at")
                .param("tenantId", tenantId.value()).query(ROW).list();
    }

    @Override
    public void touchLastUsed(UUID id, Instant now) {
        jdbc.sql("UPDATE api_clients SET last_used_at = :now WHERE id = :id").param("id", id).param("now", timestamp(now)).update();
    }
}
