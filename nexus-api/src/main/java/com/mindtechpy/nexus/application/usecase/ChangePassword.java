package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.Map;
import java.util.UUID;

/** The authenticated user changes their own password; clears the must-change flag. */
public class ChangePassword {

    private final UserRepository users;
    private final PasswordHasherPort hasher;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public ChangePassword(UserRepository users, PasswordHasherPort hasher, AuditRepository audit,
                          ClockPort clock, TransactionRunner tx) {
        this.users = users;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public void execute(Actor actor, String currentPassword, String newPassword) {
        User user = users.findById(UUID.fromString(actor.id())).orElseThrow(() -> DomainException.notFound("Usuario"));
        if (currentPassword == null || !hasher.matches(currentPassword, user.passwordHash())) {
            throw DomainException.invalid("invalid-current-password", "La contraseña actual no es correcta");
        }
        User.requireAcceptablePassword(newPassword);
        if (newPassword.equals(currentPassword)) {
            throw DomainException.invalid("password-reused", "La nueva contraseña debe ser distinta de la actual");
        }
        User updated = user.withNewPassword(hasher.hash(newPassword));
        tx.inTransaction(() -> {
            users.update(updated);
            audit.append(AuditEvent.of(actor, user.tenantId(), "user.password-changed", "user", user.id().toString(),
                    null, null, clock.now()));
            return updated;
        });
    }
}
