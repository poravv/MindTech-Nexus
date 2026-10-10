package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.TenantRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.tenant.Tenant;

import java.util.Map;

/** Registers a new tenant. Only {@code platform_admin}. */
public class CreateTenant {

    private final TenantRepository tenants;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public CreateTenant(TenantRepository tenants, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        this.tenants = tenants;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public Tenant execute(Actor actor, String slug, String name) {
        actor.require(Permission.MANAGE_TENANTS);
        Tenant tenant = Tenant.create(slug, name, clock.now());
        return tx.inTransaction(() -> {
            if (tenants.existsBySlug(tenant.slug())) {
                throw DomainException.conflict("slug-taken", "Ya existe un tenant con ese slug");
            }
            tenants.insert(tenant);
            audit.append(AuditEvent.of(actor, tenant.id(), "tenant.created", "tenant", tenant.id().toString(),
                    null, Map.of("slug", tenant.slug(), "name", tenant.name()), clock.now()));
            return tenant;
        });
    }
}
