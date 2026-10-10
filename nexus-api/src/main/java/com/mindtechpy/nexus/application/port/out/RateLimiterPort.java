package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.integration.IntegrationPolicy;

import java.util.UUID;

/**
 * Per-integration token bucket ({@code ratePerSecond}, {@code burst}) plus in-flight limit
 * ({@code maxInFlight}). Every successful {@link #tryAcquire} must be paired with {@link #release}.
 */
public interface RateLimiterPort {

    /** Takes one token and one in-flight slot, or nothing at all when either is exhausted. */
    boolean tryAcquire(UUID integrationId, IntegrationPolicy policy);

    void release(UUID integrationId);
}
