package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.InboundEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.timestamp;

@Repository
class JdbcInboundEventRepository implements InboundEventRepository {

    private final JdbcClient jdbc;

    JdbcInboundEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(InboundEvent event) {
        return jdbc.sql("""
                        INSERT INTO inbound_events (id, tenant_id, connection_id, source, event_id, event_type, received_at,
                                                    operation_id)
                        VALUES (:id, :tenantId, :connectionId, :source, :eventId, :eventType, :receivedAt, :operationId)
                        ON CONFLICT (connection_id, event_id) DO NOTHING""")
                .param("id", event.id())
                .param("tenantId", event.tenantId().value())
                .param("connectionId", event.connectionId())
                .param("source", event.source())
                .param("eventId", event.eventId())
                .param("eventType", event.eventType())
                .param("receivedAt", timestamp(event.receivedAt()))
                .param("operationId", event.operationId())
                .update() == 1;
    }
}
