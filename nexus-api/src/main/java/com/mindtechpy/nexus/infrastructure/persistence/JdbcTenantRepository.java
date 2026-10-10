package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.TenantRepository;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.domain.tenant.Tenant;
import com.mindtechpy.nexus.domain.tenant.TenantStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.*;

@Repository
class JdbcTenantRepository implements TenantRepository {

    private static final RowMapper<Tenant> ROW = (rs, n) -> new Tenant(new TenantId(uuid(rs, "id")), rs.getString("slug"),
            rs.getString("name"), TenantStatus.valueOf(rs.getString("status")), instant(rs, "created_at"));

    private final JdbcClient jdbc;

    JdbcTenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Tenant tenant) {
        jdbc.sql("INSERT INTO tenants (id, slug, name, status, created_at, updated_at) VALUES (:id, :slug, :name, :status, :now, :now)")
                .param("id", tenant.id().value())
                .param("slug", tenant.slug())
                .param("name", tenant.name())
                .param("status", tenant.status().name())
                .param("now", timestamp(tenant.createdAt()))
                .update();
    }

    @Override
    public boolean existsBySlug(String slug) {
        return jdbc.sql("SELECT count(*) FROM tenants WHERE slug = :slug").param("slug", slug).query(Long.class).single() > 0;
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return jdbc.sql("SELECT * FROM tenants WHERE id = :id").param("id", id.value()).query(ROW).optional();
    }

    @Override
    public List<Tenant> findAll() {
        return jdbc.sql("SELECT * FROM tenants ORDER BY slug").query(ROW).list();
    }
}
