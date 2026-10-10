package com.mindtechpy.nexus.infrastructure.ratelimit;

import com.mindtechpy.nexus.application.port.out.RateLimiterPort;
import com.mindtechpy.nexus.domain.integration.IntegrationPolicy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket + in-flight counter per integration, in process memory (ADR-002).
 *
 * <p>Ceiling: limits hold only with ONE replica running the worker (the v1 deployment). With N replicas
 * every one keeps its own buckets and the effective limits multiply by N; before scaling out, reimplement
 * {@link RateLimiterPort} on Postgres ({@code UPDATE … RETURNING} over a buckets table) or Redis.
 * Policy changes apply on the next call.
 */
@Component
class InMemoryRateLimiter implements RateLimiterPort {

    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(UUID integrationId, IntegrationPolicy policy) {
        return buckets.computeIfAbsent(integrationId, id -> new Bucket(policy.burst())).tryAcquire(policy, System.nanoTime());
    }

    @Override
    public void release(UUID integrationId) {
        Bucket bucket = buckets.get(integrationId);
        if (bucket != null) {
            bucket.release();
        }
    }

    private static final class Bucket {

        private double tokens;
        private long refilledAt;
        private int inFlight;

        Bucket(int burst) {
            this.tokens = burst;
            this.refilledAt = System.nanoTime();
        }

        synchronized boolean tryAcquire(IntegrationPolicy policy, long now) {
            double elapsedSeconds = (now - refilledAt) / 1e9;
            tokens = Math.min(policy.burst(), tokens + elapsedSeconds * policy.ratePerSecond());
            refilledAt = now;
            if (tokens < 1 || inFlight >= policy.maxInFlight()) {
                return false;
            }
            tokens -= 1;
            inFlight++;
            return true;
        }

        synchronized void release() {
            inFlight = Math.max(0, inFlight - 1);
        }
    }
}
