package com.mindtechpy.nexus.domain;

import com.mindtechpy.nexus.domain.identity.PasswordHash;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserLockoutTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void should_not_lock_when_fewer_than_five_failures() {
        User user = failTimes(4);

        assertThat(user.isLocked(NOW)).isFalse();
    }

    @Test
    void should_lock_progressively_when_failures_continue() {
        assertThat(failTimes(5).lockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(failTimes(6).lockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        assertThat(failTimes(8).lockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(8)));
        assertThat(failTimes(40).lockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(1024)));
    }

    @Test
    void should_reset_counter_when_login_succeeds() {
        User user = failTimes(3).withSuccessfulLogin();

        assertThat(user.failedAttempts()).isZero();
        assertThat(user.lockedUntil()).isNull();
    }

    private static User failTimes(int times) {
        User user = User.create(new TenantId(UUID.randomUUID()), "ana@example.com", new PasswordHash("x"), Role.OPERATOR);
        for (int i = 0; i < times; i++) {
            user = user.withFailedLogin(NOW);
        }
        return user;
    }
}
