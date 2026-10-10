package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectionRepository {

    void insert(Connection connection);

    void update(Connection connection);

    boolean existsByName(TenantId tenantId, ConnectorType connector, String name, UUID excludingId);

    Optional<Connection> findById(TenantId tenantId, UUID id);

    /**
     * Lookup without tenant, only for inbound webhooks: the path carries the connection id and the request
     * is authenticated afterwards with that connection's secret.
     */
    Optional<Connection> findForWebhook(UUID id);

    List<Connection> findAll(TenantId tenantId);

    void recordTest(TenantId tenantId, UUID id, Instant testedAt, boolean ok);
}
