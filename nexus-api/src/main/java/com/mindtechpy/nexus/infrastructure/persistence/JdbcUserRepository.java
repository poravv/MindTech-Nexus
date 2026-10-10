package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.identity.PasswordHash;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.*;

@Repository
class JdbcUserRepository implements UserRepository {

    private static final RowMapper<User> ROW = (rs, n) -> new User(uuid(rs, "id"), TenantId.of(uuid(rs, "tenant_id")),
            rs.getString("email"), new PasswordHash(rs.getString("password_hash")), Role.fromValue(rs.getString("role")),
            User.Status.valueOf(rs.getString("status")), rs.getBoolean("must_change_password"),
            rs.getInt("failed_attempts"), instant(rs, "locked_until"));

    private final JdbcClient jdbc;

    JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(User user) {
        jdbc.sql("""
                        INSERT INTO users (id, tenant_id, email, password_hash, role, status, must_change_password, failed_attempts, locked_until)
                        VALUES (:id, :tenantId, :email, :hash, :role, :status, :mustChange, :failed, :lockedUntil)""")
                .param("id", user.id())
                .param("tenantId", user.tenantId() == null ? null : user.tenantId().value())
                .param("email", user.email())
                .param("hash", user.passwordHash().value())
                .param("role", user.role().value())
                .param("status", user.status().name())
                .param("mustChange", user.mustChangePassword())
                .param("failed", user.failedAttempts())
                .param("lockedUntil", timestamp(user.lockedUntil()))
                .update();
    }

    @Override
    public void update(User user) {
        jdbc.sql("""
                        UPDATE users SET password_hash = :hash, role = :role, status = :status, must_change_password = :mustChange,
                               failed_attempts = :failed, locked_until = :lockedUntil, updated_at = now()
                        WHERE id = :id""")
                .param("id", user.id())
                .param("hash", user.passwordHash().value())
                .param("role", user.role().value())
                .param("status", user.status().name())
                .param("mustChange", user.mustChangePassword())
                .param("failed", user.failedAttempts())
                .param("lockedUntil", timestamp(user.lockedUntil()))
                .update();
    }

    @Override
    public Optional<User> findByEmail(String normalizedEmail) {
        return jdbc.sql("SELECT * FROM users WHERE lower(email) = lower(:email)").param("email", normalizedEmail).query(ROW).optional();
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id).query(ROW).optional();
    }

    @Override
    public List<User> findAll(Optional<TenantId> tenantId) {
        if (tenantId.isEmpty()) {
            return jdbc.sql("SELECT * FROM users ORDER BY email").query(ROW).list();
        }
        return jdbc.sql("SELECT * FROM users WHERE tenant_id = :tenantId ORDER BY email")
                .param("tenantId", tenantId.get().value()).query(ROW).list();
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT count(*) FROM users").query(Long.class).single();
    }
}
