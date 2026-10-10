package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.identity.UserSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {

    void insert(UserSession session);

    /** Session with this refresh hash that is neither revoked nor expired at {@code now}. */
    Optional<UserSession> findActive(String refreshHash, Instant now);

    void replaceRefreshHash(UUID sessionId, String newRefreshHash);

    void revoke(UUID sessionId, Instant now);
}
