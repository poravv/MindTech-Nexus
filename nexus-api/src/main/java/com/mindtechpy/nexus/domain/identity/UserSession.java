package com.mindtechpy.nexus.domain.identity;


import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** A console refresh session. Only the SHA-256 of the opaque refresh token is stored. */
public record UserSession(UUID id, UUID userId, String refreshHash, Instant expiresAt, String ip, String userAgent) {

    public static final Duration LIFETIME = Duration.ofHours(8);

    public static UserSession open(UUID userId, String refreshHash, Instant now, String ip, String userAgent) {
        return new UserSession(UUID.randomUUID(), userId, refreshHash, now.plus(LIFETIME), ip, userAgent);
    }
}
