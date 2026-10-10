package com.mindtechpy.nexus.application.saga;

import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.Operation;

/**
 * Everything a step needs, resolved by the engine right before it runs: the operation (with
 * {@code currentStep} set), the integration read fresh, and its connections with decrypted credentials.
 * {@code source} is null when the integration has no source connection.
 */
public record StepContext(Operation operation, Integration integration, Endpoint source, Endpoint target) {

    public record Endpoint(Connection connection, ConnectionCredentials credentials) {
        @Override
        public String toString() {
            return "Endpoint[connection=" + connection.id() + ", credentials=***]";
        }
    }

    public String step() {
        return operation.currentStep();
    }
}
