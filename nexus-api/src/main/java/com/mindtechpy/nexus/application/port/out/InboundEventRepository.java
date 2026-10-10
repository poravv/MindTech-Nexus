package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.UUID;

/** Webhook deliveries already received, for deduplication by the sender's delivery id. */
public interface InboundEventRepository {

    /** @param source {@code SIFEN} or {@code GIFTHUB}; {@code operationId} is the operation it refers to, if any */
    record InboundEvent(UUID id, TenantId tenantId, UUID connectionId, String source, String eventId, String eventType,
                        UUID operationId, Instant receivedAt) {
    }

    /** False when {@code (connectionId, eventId)} was already recorded: a redelivery. */
    boolean insertIfAbsent(InboundEvent event);
}
