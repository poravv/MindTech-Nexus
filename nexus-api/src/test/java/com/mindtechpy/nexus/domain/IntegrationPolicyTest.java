package com.mindtechpy.nexus.domain;

import com.mindtechpy.nexus.domain.integration.IntegrationPolicy;
import com.mindtechpy.nexus.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationPolicyTest {

    private final IntegrationPolicy policy = IntegrationPolicy.defaults();

    @Test
    void should_take_spec_defaults_when_values_are_missing() {
        assertThat(policy.ratePerSecond()).isEqualTo(2.0);
        assertThat(policy.burst()).isEqualTo(5);
        assertThat(policy.maxInFlight()).isEqualTo(2);
        assertThat(policy.maxAttempts()).isEqualTo(5);
        assertThat(policy.baseDelaySeconds()).isEqualTo(5);
        assertThat(policy.maxDelaySeconds()).isEqualTo(300);
        assertThat(policy.readTimeoutMs()).isEqualTo(150_000);
    }

    @Test
    void should_double_delay_and_cap_at_max_when_attempts_grow() {
        double noJitter = 0.5;

        assertThat(policy.retryDelay(1, noJitter)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.retryDelay(2, noJitter)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.retryDelay(3, noJitter)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.retryDelay(20, noJitter)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void should_keep_jitter_within_twenty_percent_when_sample_is_extreme() {
        assertThat(policy.retryDelay(2, 0.0)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.retryDelay(2, 0.999_999)).isBetween(Duration.ofMillis(11_990), Duration.ofSeconds(12));
    }

    @Test
    void should_reject_policy_when_limits_are_not_positive() {
        assertThatThrownBy(() -> new IntegrationPolicy(0.0, null, null, null, null, null, null, null))
                .isInstanceOf(DomainException.class).hasMessageContaining("ratePerSecond");
        assertThatThrownBy(() -> new IntegrationPolicy(null, null, null, null, 10, 5, null, null))
                .isInstanceOf(DomainException.class).hasMessageContaining("maxDelaySeconds");
    }

    @Test
    void should_reject_read_timeout_that_outlasts_the_job_lease() {
        assertThat(new IntegrationPolicy(null, null, null, null, null, null, 540_000, null).readTimeoutMs()).isEqualTo(540_000);
        assertThatThrownBy(() -> new IntegrationPolicy(null, null, null, null, null, null, 540_001, null))
                .isInstanceOf(DomainException.class).hasMessageContaining("readTimeoutMs");
    }
}
