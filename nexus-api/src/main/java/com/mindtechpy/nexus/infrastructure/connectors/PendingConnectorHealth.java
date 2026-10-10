package com.mindtechpy.nexus.infrastructure.connectors;

import com.mindtechpy.nexus.application.port.out.ConnectorHealthPort;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import org.springframework.stereotype.Component;

/**
 * S1 placeholder: real connectors (SIFEN in S3, POS/GiftHub in S5) replace it with a harmless GET.
 * Reaching this point already proves the stored credentials decrypt.
 */
@Component
class PendingConnectorHealth implements ConnectorHealthPort {

    @Override
    public HealthCheck check(Connection connection, ConnectionCredentials credentials) {
        return new HealthCheck(true, "Credenciales descifradas; la verificación remota de "
                + connection.connector() + " se habilita con su conector");
    }
}
