package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.ConnectorHealthPort;
import com.mindtechpy.nexus.application.port.out.ConnectorHealthPort.HealthCheck;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Checks that the credentials decrypt and that the remote system answers. Reports only ok/detail. */
public class TestConnection {

    public record Result(boolean ok, String detail, Instant testedAt) {
    }

    private final ConnectionRepository connections;
    private final SecretCipherPort cipher;
    private final ConnectorHealthPort health;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public TestConnection(ConnectionRepository connections, SecretCipherPort cipher, ConnectorHealthPort health,
                          AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        this.connections = connections;
        this.cipher = cipher;
        this.health = health;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public Result execute(Actor actor, TenantId requestedTenant, UUID id) {
        actor.require(Permission.MANAGE_CONNECTIONS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        Connection connection = connections.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Conexión"));
        HealthCheck check = check(connection);
        Instant testedAt = clock.now();
        tx.inTransaction(() -> {
            connections.recordTest(tenantId, id, testedAt, check.ok());
            audit.append(AuditEvent.of(actor, tenantId, "connection.tested", "connection", id.toString(), null,
                    Map.of("ok", check.ok()), testedAt));
            return check;
        });
        return new Result(check.ok(), check.detail(), testedAt);
    }

    private HealthCheck check(Connection connection) {
        ConnectionCredentials credentials;
        try {
            credentials = cipher.open(connection.sealedCredentials(), connection.credentialsAad());
        } catch (DomainException e) {
            return new HealthCheck(false, "Credenciales ilegibles: vuelva a cargarlas");
        }
        return health.check(connection, credentials);
    }
}
