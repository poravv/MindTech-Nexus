package com.mindtechpy.nexus.domain.identity;

import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** A console user. {@code tenantId} is null only for {@code platform_admin}. */
public record User(UUID id, TenantId tenantId, String email, PasswordHash passwordHash, Role role,
                   Status status, boolean mustChangePassword, int failedAttempts, Instant lockedUntil) {

    public enum Status { ACTIVE, DISABLED }

    public static final int MIN_PASSWORD_LENGTH = 12;
    private static final int FREE_ATTEMPTS = 5;
    private static final int MAX_LOCK_EXPONENT = 10;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public static User create(TenantId tenantId, String email, PasswordHash hash, Role role) {
        if (role == Role.PLATFORM_ADMIN && tenantId != null) {
            throw DomainException.invalid("invalid-tenant", "platform_admin no pertenece a un tenant");
        }
        if (role != Role.PLATFORM_ADMIN && tenantId == null) {
            throw DomainException.invalid("tenant-required", "El rol " + role.value() + " requiere tenant");
        }
        return new User(UUID.randomUUID(), tenantId, normalizeEmail(email), hash, role, Status.ACTIVE, true, 0, null);
    }

    public static String normalizeEmail(String email) {
        if (email == null || !EMAIL.matcher(email.strip()).matches()) {
            throw DomainException.invalid("invalid-email", "Email inválido");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static void requireAcceptablePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw DomainException.invalid("weak-password", "La contraseña debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres");
        }
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Progressive lockout: from the 5th consecutive failure the lock doubles (1, 2, 4 … 1024 min). */
    public User withFailedLogin(Instant now) {
        int failures = failedAttempts + 1;
        Instant lock = lockedUntil;
        if (failures >= FREE_ATTEMPTS) {
            long minutes = 1L << Math.min(failures - FREE_ATTEMPTS, MAX_LOCK_EXPONENT);
            lock = now.plus(Duration.ofMinutes(minutes));
        }
        return new User(id, tenantId, email, passwordHash, role, status, mustChangePassword, failures, lock);
    }

    public User withSuccessfulLogin() {
        return new User(id, tenantId, email, passwordHash, role, status, mustChangePassword, 0, null);
    }

    public User withNewPassword(PasswordHash hash) {
        return new User(id, tenantId, email, hash, role, status, false, 0, null);
    }
}
