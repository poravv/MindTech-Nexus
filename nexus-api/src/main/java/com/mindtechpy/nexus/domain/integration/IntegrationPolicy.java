package com.mindtechpy.nexus.domain.integration;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.time.Duration;

/**
 * Per-integration limits and retry settings (spec §6, stored as jsonb). Missing values take the spec
 * defaults; a missing {@code readTimeoutMs} takes its flow's default when the policy joins an
 * {@link Integration}. {@code scheduleEverySeconds} only applies to CATALOG.
 */
public record IntegrationPolicy(Double ratePerSecond, Integer burst, Integer maxInFlight, Integer maxAttempts,
                                Integer baseDelaySeconds, Integer maxDelaySeconds, Integer readTimeoutMs,
                                Integer scheduleEverySeconds) {

    private static final double JITTER = 0.2;
    /**
     * A step makes at most two remote calls (VERIFY + SUBMIT, catalog read + push): two reads at this cap still
     * end inside the 10 min lease of a claimed operation.
     */
    private static final int MAX_READ_TIMEOUT_MS = 280_000;

    public IntegrationPolicy {
        ratePerSecond = ratePerSecond == null ? 2.0 : ratePerSecond;
        burst = burst == null ? 5 : burst;
        maxInFlight = maxInFlight == null ? 2 : maxInFlight;
        maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        baseDelaySeconds = baseDelaySeconds == null ? 5 : baseDelaySeconds;
        maxDelaySeconds = maxDelaySeconds == null ? 300 : maxDelaySeconds;
        require(ratePerSecond > 0, "ratePerSecond debe ser mayor a 0");
        require(burst >= 1, "burst debe ser al menos 1");
        require(maxInFlight >= 1, "maxInFlight debe ser al menos 1");
        require(maxAttempts >= 1 && maxAttempts <= 100, "maxAttempts debe estar entre 1 y 100");
        require(baseDelaySeconds >= 1, "baseDelaySeconds debe ser al menos 1");
        require(maxDelaySeconds >= baseDelaySeconds, "maxDelaySeconds no puede ser menor que baseDelaySeconds");
        require(readTimeoutMs == null || readTimeoutMs >= 1000 && readTimeoutMs <= MAX_READ_TIMEOUT_MS,
                "readTimeoutMs debe estar entre 1000 y 280000");
        require(scheduleEverySeconds == null || scheduleEverySeconds >= 60, "scheduleEverySeconds debe ser al menos 60");
    }

    public static IntegrationPolicy defaults() {
        return new IntegrationPolicy(null, null, null, null, null, null, null, null);
    }

    /** SIFEN can hold a FISCAL call ~183 s (30 s read + 3 queries of 45 s); the other connectors answer well within 150 s. */
    public IntegrationPolicy withDefaultReadTimeout(Flow flow) {
        if (readTimeoutMs != null) {
            return this;
        }
        return new IntegrationPolicy(ratePerSecond, burst, maxInFlight, maxAttempts, baseDelaySeconds, maxDelaySeconds,
                flow == Flow.FISCAL ? 200_000 : 150_000, scheduleEverySeconds);
    }

    /**
     * Exponential backoff {@code min(maxDelay, base * 2^(attempt-1))} with ±20 % jitter.
     *
     * @param attempt       attempt that just failed (1-based)
     * @param jitterSample  uniform random value in [0, 1)
     */
    public Duration retryDelay(int attempt, double jitterSample) {
        double exponential = baseDelaySeconds * Math.pow(2, Math.max(0, attempt - 1));
        double capped = Math.min(maxDelaySeconds, exponential);
        double jittered = capped * (1 - JITTER + 2 * JITTER * jitterSample);
        return Duration.ofMillis(Math.round(jittered * 1000));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw DomainException.invalid("invalid-policy", message);
        }
    }
}
