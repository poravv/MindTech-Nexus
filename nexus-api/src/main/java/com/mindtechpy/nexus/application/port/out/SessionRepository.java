package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.identity.UserSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {

    void insert(UserSession session);

    /** Session with this refresh hash that is neither revoked nor expired at {@code now}. */
    Optional<UserSession> findActive(String refreshHash, Instant now);

    /**
     * Swaps the refresh hash only while {@code oldRefreshHash} is still the active one: of two concurrent
     * refreshes with the same token exactly one wins. False when the token was already used, revoked or expired.
     */
    boolean rotateRefreshHash(String oldRefreshHash, String newRefreshHash, Instant now);

    void revoke(UUID sessionId, Instant now);
}
