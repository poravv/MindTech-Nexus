package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.application.port.out.TenantRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.connection.BaseUrlPolicy;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creates or edits a connection. Credentials are write-only: they are sealed with AES-GCM bound to
 * {@code tenant:connection} and never returned; editing without credentials keeps the current ones.
 */
public class UpsertConnection {

    public record Draft(String name, String baseUrl, Map<String, String> credentials, Map<String, Object> settings) {
        @Override
        public String toString() {
            return "Draft[name=" + name + ", baseUrl=" + baseUrl + ", credentials=***]";
        }
    }

    private final ConnectionRepository connections;
    private final TenantRepository tenants;
    private final SecretCipherPort cipher;
    private final BaseUrlPolicy baseUrlPolicy;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public UpsertConnection(ConnectionRepository connections, TenantRepository tenants, SecretCipherPort cipher,
                            BaseUrlPolicy baseUrlPolicy, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        this.connections = connections;
        this.tenants = tenants;
        this.cipher = cipher;
        this.baseUrlPolicy = baseUrlPolicy;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public Connection create(Actor actor, TenantId requestedTenant, ConnectorType connector, Draft draft) {
        actor.require(Permission.MANAGE_CONNECTIONS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        if (tenants.findById(tenantId).isEmpty()) {
            throw DomainException.notFound("Tenant");
        }
        if (connector == null) {
            throw DomainException.invalid("invalid-connector", "El conector es obligatorio");
        }
        UUID id = UUID.randomUUID();
        byte[] sealed = cipher.seal(new ConnectionCredentials(draft.credentials()), Connection.credentialsAad(tenantId, id));
        Connection connection = new Connection(id, tenantId, connector, draft.name(), baseUrlPolicy.validate(draft.baseUrl()),
                sealed, draft.settings(), null, null);
        return tx.inTransaction(() -> {
            requireUniqueName(connection);
            connections.insert(connection);
            audit.append(AuditEvent.of(actor, tenantId, "connection.created", "connection", id.toString(), null,
                    describe(connection, true), clock.now()));
            return connection;
        });
    }

    public Connection update(Actor actor, TenantId requestedTenant, UUID id, Draft draft) {
        actor.require(Permission.MANAGE_CONNECTIONS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        Connection current = connections.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Conexión"));
        boolean credentialsChanged = draft.credentials() != null;
        byte[] sealed = credentialsChanged
                ? cipher.seal(new ConnectionCredentials(draft.credentials()), current.credentialsAad())
                : current.sealedCredentials();
        Connection updated = new Connection(id, tenantId, current.connector(), draft.name(),
                baseUrlPolicy.validate(draft.baseUrl()), sealed,
                draft.settings() == null ? current.settings() : draft.settings(),
                current.lastTestAt(), current.lastTestOk());
        return tx.inTransaction(() -> {
            requireUniqueName(updated);
            connections.update(updated);
            audit.append(AuditEvent.of(actor, tenantId, "connection.updated", "connection", id.toString(),
                    describe(current, false), describe(updated, credentialsChanged), clock.now()));
            return updated;
        });
    }

    private void requireUniqueName(Connection connection) {
        if (connections.existsByName(connection.tenantId(), connection.connector(), connection.name(), connection.id())) {
            throw DomainException.conflict("connection-name-taken", "Ya existe una conexión con ese nombre para el conector");
        }
    }

    private static Map<String, Object> describe(Connection connection, boolean credentialsChanged) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("connector", connection.connector().name());
        view.put("name", connection.name());
        view.put("baseUrl", connection.baseUrl());
        view.put("settingsKeys", connection.settings().keySet().stream().sorted().toList());
        view.put("credentialsChanged", credentialsChanged);
        return view;
    }
}
