package com.mindtechpy.nexus.domain.integration;

import com.mindtechpy.nexus.domain.operation.OperationStatus;

import java.util.Map;

/** Health of an integration in the Argo CD sense, as the console shows it. */
public enum IntegrationHealth {
    HEALTHY, PROGRESSING, DEGRADED, SUSPENDED, NO_ACTIVITY;

    /**
     * Derived from the state and the operations created in the window, checked in this order:
     * a stopped integration ({@code PAUSED}, {@code DISABLED}, {@code EMERGENCY_STOP}) is suspended whatever its
     * history; any {@code FAILED} or {@code DEAD} → degraded; draining or any operation not yet {@code SUCCEEDED}
     * → progressing; only successes → healthy; no operations → no activity.
     */
    public static IntegrationHealth of(IntegrationState state, Map<OperationStatus, Long> recent) {
        if (state == IntegrationState.PAUSED || state == IntegrationState.DISABLED || state == IntegrationState.EMERGENCY_STOP) {
            return SUSPENDED;
        }
        long total = recent.values().stream().mapToLong(Long::longValue).sum();
        long succeeded = recent.getOrDefault(OperationStatus.SUCCEEDED, 0L);
        if (recent.getOrDefault(OperationStatus.FAILED, 0L) + recent.getOrDefault(OperationStatus.DEAD, 0L) > 0) {
            return DEGRADED;
        }
        if (state == IntegrationState.DRAINING || total > succeeded) {
            return PROGRESSING;
        }
        return total > 0 ? HEALTHY : NO_ACTIVITY;
    }
}
