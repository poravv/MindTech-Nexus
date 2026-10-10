package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;

public class ListAuditEvents {

    public static final int MAX_PAGE_SIZE = 200;

    private final AuditRepository audit;

    public ListAuditEvents(AuditRepository audit) {
        this.audit = audit;
    }

    /** Newest first; {@code page} is zero-based and {@code size} is capped at {@link #MAX_PAGE_SIZE}. */
    public List<AuditEvent> execute(Actor actor, TenantId requestedTenant, int page, int size) {
        actor.require(Permission.READ_AUDIT);
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return audit.find(actor.tenantFilter(requestedTenant), limit, Math.max(page, 0) * limit);
    }
}
