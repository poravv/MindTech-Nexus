package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.ListAuditEvents;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin · Auditoría")
@RestController
@RequestMapping("/api/admin/audit")
class AdminAuditController {

    record AuditEventView(UUID id, UUID tenantId, String actorType, String actorId, String action, String resourceType,
                          String resourceId, Map<String, Object> before, Map<String, Object> after,
                          String correlationId, Instant happenedAt) {
        static AuditEventView of(AuditEvent event) {
            return new AuditEventView(event.id(), event.tenantId() == null ? null : event.tenantId().value(),
                    event.actorType().name(), event.actorId(), event.action(), event.resourceType(), event.resourceId(),
                    event.before(), event.after(), event.correlationId(), event.happenedAt());
        }
    }

    private final ListAuditEvents listAuditEvents;

    AdminAuditController(ListAuditEvents listAuditEvents) {
        this.listAuditEvents = listAuditEvents;
    }

    @Operation(summary = "Eventos de auditoría, más recientes primero (platform_admin, tenant_admin, auditor)")
    @GetMapping
    List<AuditEventView> list(@RequestParam(required = false) UUID tenantId,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size) {
        return listAuditEvents.execute(TenantContext.currentUser(), TenantId.of(tenantId), page, size)
                .stream().map(AuditEventView::of).toList();
    }
}
