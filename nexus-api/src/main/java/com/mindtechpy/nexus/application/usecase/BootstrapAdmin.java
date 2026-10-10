package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.identity.User;

import java.util.Map;

/** Creates the first {@code platform_admin} when the database has no users; it must change the password at first login. */
public class BootstrapAdmin {

    private final UserRepository users;
    private final PasswordHasherPort hasher;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public BootstrapAdmin(UserRepository users, PasswordHasherPort hasher, AuditRepository audit,
                          ClockPort clock, TransactionRunner tx) {
        this.users = users;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    /** @return true if the admin was created, false if users already existed. */
    public boolean execute(String email, String password) {
        User.requireAcceptablePassword(password);
        return tx.inTransaction(() -> {
            if (users.count() > 0) {
                return false;
            }
            User admin = User.create(null, email, hasher.hash(password), Role.PLATFORM_ADMIN);
            users.insert(admin);
            audit.append(AuditEvent.of(Actor.system(), null, "user.bootstrapped", "user", admin.id().toString(),
                    null, Map.of("email", admin.email(), "role", Role.PLATFORM_ADMIN.value()), clock.now()));
            return true;
        });
    }
}
