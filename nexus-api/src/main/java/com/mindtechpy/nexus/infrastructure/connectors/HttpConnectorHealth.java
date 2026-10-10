package com.mindtechpy.nexus.infrastructure.connectors;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.ConnectorHealthPort;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp.Response;
import com.mindtechpy.nexus.infrastructure.connectors.sifen.SifenHmacSigner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One authenticated, read-only GET per connector, the cheapest each API offers: POS
 * {@code GET /api/integration/catalog?page=1&limit=1} ({@code X-Api-Key}), GiftHub
 * {@code GET /api/integrations/v1/products?limit=1} (Bearer) and SIFEN {@code GET /api/v1/facturas?page=0&size=1}
 * (access/secret key plus HMAC, scoped to the key's emisor). A 2xx proves address and credentials; the detail
 * names the HTTP status or transport failure and never carries credentials or the remote body.
 */
@Component
class HttpConnectorHealth implements ConnectorHealthPort {

    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    static final String POS_PATH = "/api/integration/catalog?page=1&limit=1";
    static final String GIFTHUB_PATH = "/api/integrations/v1/products?limit=1";
    static final String SIFEN_PATH = "/api/v1/facturas?page=0&size=1";

    private final ConnectorHttp http;

    HttpConnectorHealth(ConnectorHttp http) {
        this.http = http;
    }

    @Override
    public HealthCheck check(Connection connection, ConnectionCredentials credentials) {
        try {
            Response response = switch (connection.connector()) {
                case POS -> get(connection, POS_PATH, headers -> headers.set("X-Api-Key",
                        ConnectorHttp.credential(credentials, "apiKey")));
                case GIFTHUB -> get(connection, GIFTHUB_PATH, headers -> headers.setBearerAuth(
                        ConnectorHttp.credential(credentials, "apiKey")));
                case SIFEN -> get(connection, SIFEN_PATH, sifenHeaders(connection, credentials));
            };
            if (response.isSuccess()) {
                return new HealthCheck(true, connection.connector() + " respondió " + response.status() + " con estas credenciales");
            }
            return new HealthCheck(false, connection.connector() + " respondió " + response.status()
                    + (response.status() == 401 || response.status() == 403 ? ": credenciales rechazadas" : ""));
        } catch (ConnectorException e) {
            return new HealthCheck(false, connection.connector() + " no verificado: " + e.error().code());
        }
    }

    private Response get(Connection connection, String path, Consumer<HttpHeaders> headers) {
        return http.call(connection.baseUrl(), READ_TIMEOUT, HttpMethod.GET, path, headers, null);
    }

    /** Same signature as every SIFEN request: over the raw path (base path included) and an empty body. */
    private static Consumer<HttpHeaders> sifenHeaders(Connection connection, ConnectionCredentials credentials) {
        String accessKey = ConnectorHttp.credential(credentials, "accessKey");
        String secretKey = ConnectorHttp.credential(credentials, "secretKey");
        URI uri = URI.create(connection.baseUrl() + SIFEN_PATH);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature = SifenHmacSigner.sign(secretKey, "GET", uri.getRawPath() + "?" + uri.getRawQuery(), timestamp,
                nonce, new byte[0]);
        return headers -> {
            headers.set("X-Access-Key", accessKey);
            headers.set("X-Secret-Key", secretKey);
            headers.set("X-Timestamp", timestamp);
            headers.set("X-Nonce", nonce);
            headers.set("X-Signature", signature);
        };
    }
}
