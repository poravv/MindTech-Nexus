package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;
import java.util.UUID;

/** Read side of connections. A connection of another tenant is reported as not found (ADR-005). */
public class ConnectionQueries {

    private final ConnectionRepository connections;

    public ConnectionQueries(ConnectionRepository connections) {
        this.connections = connections;
    }

    public List<Connection> list(Actor actor, TenantId requestedTenant) {
        actor.require(Permission.READ_TENANT_DATA);
        return connections.findAll(actor.targetTenant(requestedTenant));
    }

    public Connection get(Actor actor, TenantId requestedTenant, UUID id) {
        actor.require(Permission.READ_TENANT_DATA);
        return connections.findById(actor.targetTenant(requestedTenant), id)
                .orElseThrow(() -> DomainException.notFound("Conexión"));
    }
}
