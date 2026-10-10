package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

public interface AuditRepository {

    /** Persists the event; the adapter stamps the current request correlation id when the event has none. */
    void append(AuditEvent event);

    /** Newest first. Empty filter means every tenant (platform_admin only). */
    List<AuditEvent> find(Optional<TenantId> tenantId, int limit, int offset);
}
