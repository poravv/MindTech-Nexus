package com.mindtechpy.nexus.domain.audit;

import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Immutable record of who changed what. {@code before}/{@code after} never contain secrets. */
public record AuditEvent(UUID id, TenantId tenantId, Actor.Type actorType, String actorId, String action,
                         String resourceType, String resourceId, Map<String, Object> before,
                         Map<String, Object> after, String correlationId, Instant happenedAt) {

    public static AuditEvent of(Actor actor, TenantId tenantId, String action, String resourceType, String resourceId,
                                Map<String, Object> before, Map<String, Object> after, Instant now) {
        return new AuditEvent(UUID.randomUUID(), tenantId, actor.type(), actor.id(), action, resourceType, resourceId,
                before, after, null, now);
    }
}
