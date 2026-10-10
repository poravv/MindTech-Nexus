package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.infrastructure.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.*;

@Repository
class JdbcAuditRepository implements AuditRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RowMapper<AuditEvent> row;

    JdbcAuditRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.row = (rs, n) -> new AuditEvent(uuid(rs, "id"), TenantId.of(uuid(rs, "tenant_id")),
                Actor.Type.valueOf(rs.getString("actor_type")), rs.getString("actor_id"), rs.getString("action"),
                rs.getString("resource_type"), rs.getString("resource_id"), map(json, rs.getString("before")),
                map(json, rs.getString("after")), rs.getString("correlation_id"), instant(rs, "happened_at"));
    }

    @Override
    public void append(AuditEvent event) {
        String correlationId = event.correlationId() != null ? event.correlationId() : MDC.get(CorrelationIdFilter.MDC_KEY);
        jdbc.sql("""
                        INSERT INTO audit_events (id, tenant_id, actor_type, actor_id, action, resource_type, resource_id,
                                                  before, after, correlation_id, happened_at)
                        VALUES (:id, :tenantId, :actorType, :actorId, :action, :resourceType, :resourceId,
                                CAST(:before AS jsonb), CAST(:after AS jsonb), :correlationId, :happenedAt)""")
                .param("id", event.id())
                .param("tenantId", event.tenantId() == null ? null : event.tenantId().value())
                .param("actorType", event.actorType().name())
                .param("actorId", event.actorId())
                .param("action", event.action())
                .param("resourceType", event.resourceType())
                .param("resourceId", event.resourceId())
                .param("before", json(json, event.before()))
                .param("after", json(json, event.after()))
                .param("correlationId", correlationId)
                .param("happenedAt", timestamp(event.happenedAt()))
                .update();
    }

    @Override
    public List<AuditEvent> find(Optional<TenantId> tenantId, int limit, int offset) {
        String where = tenantId.isPresent() ? " WHERE tenant_id = :tenantId" : "";
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT * FROM audit_events" + where
                        + " ORDER BY happened_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("limit", limit)
                .param("offset", offset);
        tenantId.ifPresent(id -> statement.param("tenantId", id.value()));
        return statement.query(row).list();
    }
}
