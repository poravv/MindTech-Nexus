package com.mindtechpy.nexus.domain.integration;

import com.mindtechpy.nexus.domain.operation.OperationStatus;

import java.util.Map;

/** Health of an integration in the Argo CD sense, as the console shows it. */
public enum IntegrationHealth {
    HEALTHY, PROGRESSING, DEGRADED, SUSPENDED, NO_ACTIVITY;

    /**
     * Checked in this order: a stopped integration ({@code PAUSED}, {@code DISABLED}, {@code EMERGENCY_STOP}) is
     * suspended whatever its history; an unresolved DLQ ({@code dead} &gt; 0, of any age, until each operation is
     * replayed or discarded) → degraded; draining or any operation of the window still in the queue → progressing;
     * any finished operation in the window → healthy; none → no activity. {@code FAILED} is a business rejection
     * the integration handled correctly, so it is counted in {@code recent} but never degrades.
     */
    public static IntegrationHealth of(IntegrationState state, Map<OperationStatus, Long> recent, long dead) {
        if (state == IntegrationState.PAUSED || state == IntegrationState.DISABLED || state == IntegrationState.EMERGENCY_STOP) {
            return SUSPENDED;
        }
        if (dead > 0) {
            return DEGRADED;
        }
        long queued = recent.entrySet().stream().filter(entry -> !entry.getKey().isFinished())
                .mapToLong(Map.Entry::getValue).sum();
        if (state == IntegrationState.DRAINING || queued > 0) {
            return PROGRESSING;
        }
        return recent.isEmpty() ? NO_ACTIVITY : HEALTHY;
    }
}
