package com.mindtechpy.nexus.domain;

import com.mindtechpy.nexus.domain.integration.IntegrationHealth;
import com.mindtechpy.nexus.domain.integration.IntegrationState;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationHealthTest {

    @Test
    void should_be_degraded_only_while_the_dlq_has_unresolved_operations() {
        assertThat(IntegrationHealth.of(IntegrationState.ENABLED, Map.of(), 1)).isEqualTo(IntegrationHealth.DEGRADED);
        assertThat(IntegrationHealth.of(IntegrationState.ENABLED, Map.of(OperationStatus.SUCCEEDED, 5L), 1))
                .isEqualTo(IntegrationHealth.DEGRADED);
        assertThat(IntegrationHealth.of(IntegrationState.ENABLED, Map.of(OperationStatus.DISCARDED, 1L), 0))
                .isEqualTo(IntegrationHealth.HEALTHY);
    }

    @Test
    void should_stay_healthy_when_the_window_only_has_business_rejections() {
        assertThat(IntegrationHealth.of(IntegrationState.ENABLED, Map.of(OperationStatus.FAILED, 3L), 0))
                .isEqualTo(IntegrationHealth.HEALTHY);
    }

    @Test
    void should_be_suspended_when_stopped_even_with_a_dlq() {
        assertThat(IntegrationHealth.of(IntegrationState.PAUSED, Map.of(), 2)).isEqualTo(IntegrationHealth.SUSPENDED);
        assertThat(IntegrationHealth.of(IntegrationState.EMERGENCY_STOP, Map.of(), 0)).isEqualTo(IntegrationHealth.SUSPENDED);
    }

    @Test
    void should_be_progressing_when_draining_or_operations_are_queued() {
        assertThat(IntegrationHealth.of(IntegrationState.DRAINING, Map.of(), 0)).isEqualTo(IntegrationHealth.PROGRESSING);
        assertThat(IntegrationHealth.of(IntegrationState.ENABLED, Map.of(OperationStatus.FAILED, 1L, OperationStatus.RETRY, 1L), 0))
                .isEqualTo(IntegrationHealth.PROGRESSING);
    }

    @Test
    void should_report_no_activity_without_operations_in_the_window() {
        assertThat(IntegrationHealth.of(IntegrationState.ENABLED, Map.of(), 0)).isEqualTo(IntegrationHealth.NO_ACTIVITY);
    }
}
