package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.SessionRepository;
import com.mindtechpy.nexus.domain.identity.UserSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.*;

@Repository
class JdbcSessionRepository implements SessionRepository {

    private final JdbcClient jdbc;

    JdbcSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UserSession session) {
        jdbc.sql("""
                        INSERT INTO user_sessions (id, user_id, refresh_hash, expires_at, ip, user_agent)
                        VALUES (:id, :userId, :hash, :expiresAt, :ip, :userAgent)""")
                .param("id", session.id())
                .param("userId", session.userId())
                .param("hash", session.refreshHash())
                .param("expiresAt", timestamp(session.expiresAt()))
                .param("ip", session.ip())
                .param("userAgent", session.userAgent())
                .update();
    }

    @Override
    public Optional<UserSession> findActive(String refreshHash, Instant now) {
        return jdbc.sql("""
                        SELECT * FROM user_sessions
                        WHERE refresh_hash = :hash AND revoked_at IS NULL AND expires_at > :now""")
                .param("hash", refreshHash)
                .param("now", timestamp(now))
                .query((rs, n) -> new UserSession(uuid(rs, "id"), uuid(rs, "user_id"), rs.getString("refresh_hash"),
                        instant(rs, "expires_at"), rs.getString("ip"), rs.getString("user_agent")))
                .optional();
    }

    @Override
    public boolean rotateRefreshHash(String oldRefreshHash, String newRefreshHash, Instant now) {
        return jdbc.sql("""
                        UPDATE user_sessions SET refresh_hash = :newHash, updated_at = now()
                        WHERE refresh_hash = :oldHash AND revoked_at IS NULL AND expires_at > :now""")
                .param("oldHash", oldRefreshHash).param("newHash", newRefreshHash).param("now", timestamp(now))
                .update() == 1;
    }

    @Override
    public void revoke(UUID sessionId, Instant now) {
        jdbc.sql("UPDATE user_sessions SET revoked_at = :now, updated_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", sessionId).param("now", timestamp(now)).update();
    }
}
