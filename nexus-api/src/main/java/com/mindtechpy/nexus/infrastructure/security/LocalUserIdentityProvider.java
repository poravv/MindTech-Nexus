package com.mindtechpy.nexus.infrastructure.security;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IdentityProviderPort;
import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.identity.PasswordHash;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Local users with bcrypt and progressive lockout. Unknown emails still pay a bcrypt comparison so
 * response time does not reveal which accounts exist.
 */
@Component
class LocalUserIdentityProvider implements IdentityProviderPort {

    private static final Logger log = LoggerFactory.getLogger(LocalUserIdentityProvider.class);

    private final UserRepository users;
    private final PasswordHasherPort hasher;
    private final ClockPort clock;
    private final PasswordHash timingDecoy;

    LocalUserIdentityProvider(UserRepository users, PasswordHasherPort hasher, ClockPort clock) {
        this.users = users;
        this.hasher = hasher;
        this.clock = clock;
        this.timingDecoy = hasher.hash("timing-decoy-not-a-password");
    }

    @Override
    public Optional<AuthenticatedUser> authenticate(Credentials credentials) {
        Optional<User> found = findByEmail(credentials.email()).filter(User::isActive);
        if (found.isEmpty()) {
            hasher.matches(credentials.password(), timingDecoy);
            return Optional.empty();
        }
        User user = found.get();
        Instant now = clock.now();
        if (user.isLocked(now)) {
            log.warn("[NEXUS-AUTH] login rechazado: cuenta bloqueada user={}", user.id());
            throw new DomainException(DomainException.Kind.LOCKED, "account-locked", "Cuenta bloqueada temporalmente");
        }
        if (!hasher.matches(credentials.password(), user.passwordHash())) {
            User failed = user.withFailedLogin(now);
            users.update(failed);
            log.warn("[NEXUS-AUTH] login fallido user={} intentos={}", user.id(), failed.failedAttempts());
            return Optional.empty();
        }
        if (user.failedAttempts() > 0) {
            users.update(user.withSuccessfulLogin());
        }
        log.info("[NEXUS-AUTH] login ok user={} role={}", user.id(), user.role().value());
        return Optional.of(new AuthenticatedUser(user.id(), user.email(), user.role(), user.tenantId(), user.mustChangePassword()));
    }

    private Optional<User> findByEmail(String email) {
        try {
            return users.findByEmail(User.normalizeEmail(email));
        } catch (DomainException invalidEmail) {
            return Optional.empty();
        }
    }
}
