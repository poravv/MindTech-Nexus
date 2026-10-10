package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;

/** Harmless reachability check against the external system of a connection. */
public interface ConnectorHealthPort {

    record HealthCheck(boolean ok, String detail) {
    }

    HealthCheck check(Connection connection, ConnectionCredentials credentials);
}
