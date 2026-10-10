package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.application.port.out.TenantRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.Map;

/**
 * Creates a console user with an initial password that must be changed at first login.
 * Only a {@code platform_admin} can create another {@code platform_admin}.
 */
public class CreateUser {

    private final UserRepository users;
    private final TenantRepository tenants;
    private final PasswordHasherPort hasher;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public CreateUser(UserRepository users, TenantRepository tenants, PasswordHasherPort hasher,
                      AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        this.users = users;
        this.tenants = tenants;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public User execute(Actor actor, TenantId requestedTenant, String email, String password, Role role) {
        actor.require(Permission.MANAGE_USERS);
        TenantId tenantId = resolveTenant(actor, requestedTenant, role);
        User.requireAcceptablePassword(password);
        User user = User.create(tenantId, email, hasher.hash(password), role);
        return tx.inTransaction(() -> {
            if (users.findByEmail(user.email()).isPresent()) {
                throw DomainException.conflict("email-taken", "Ya existe un usuario con ese email");
            }
            users.insert(user);
            audit.append(AuditEvent.of(actor, tenantId, "user.created", "user", user.id().toString(),
                    null, Map.of("email", user.email(), "role", role.value()), clock.now()));
            return user;
        });
    }

    private TenantId resolveTenant(Actor actor, TenantId requestedTenant, Role role) {
        if (role == Role.PLATFORM_ADMIN) {
            if (!actor.isPlatformAdmin()) {
                throw DomainException.forbidden("forbidden", "Solo platform_admin puede crear platform_admin");
            }
            return null;
        }
        TenantId tenantId = actor.targetTenant(requestedTenant);
        if (tenants.findById(tenantId).isEmpty()) {
            throw DomainException.notFound("Tenant");
        }
        return tenantId;
    }
}
