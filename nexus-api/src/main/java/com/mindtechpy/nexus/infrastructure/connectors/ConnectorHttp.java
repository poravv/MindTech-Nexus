package com.mindtechpy.nexus.infrastructure.connectors;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.ErrorMessages;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Plain JSON over HTTP for the POS and GiftHub connectors: connect timeout {@value #CONNECT_SECONDS} s, read
 * timeout per integration policy, redirects never followed (spec §5.4). Transport failures are classified
 * here (connection never established → TRANSIENT; anything after the request left → UNCERTAIN); HTTP
 * statuses are classified by each connector, with {@link #commonError} for the shared ones.
 */
@Component
public class ConnectorHttp {

    static final int CONNECT_SECONDS = 5;

    /** {@code body} is {@link MissingNode} when the response had no JSON body. */
    public record Response(int status, JsonNode body, String retryAfter) {
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final JsonMapper json;
    private final MeterRegistry meters;

    public ConnectorHttp(JsonMapper json, MeterRegistry meters) {
        this.json = json;
        this.meters = meters;
    }

    /**
     * Runs one connector operation (its calls and the classification of the answer) and records
     * {@code nexus_connector_latency_seconds{connector,operation,outcome}}: {@code ok}, or the kind of the
     * {@link ConnectorException} it threw, as SIFEN does.
     */
    public <T> T timed(String connector, String operation, Supplier<T> call) {
        long started = System.nanoTime();
        String outcome = "ok";
        try {
            return call.get();
        } catch (ConnectorException e) {
            outcome = e.error().kind().name().toLowerCase();
            throw e;
        } finally {
            Timer.builder("nexus.connector.latency").tag("connector", connector).tag("operation", operation)
                    .tag("outcome", outcome).register(meters).record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    public Response call(String baseUrl, Duration readTimeout, HttpMethod method, String path, Consumer<HttpHeaders> headers,
                         Object body) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        RestClient client = RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
        RestClient.RequestBodySpec request = client.method(method).uri(path).headers(headers).accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsBytes(body));
        }
        try {
            return request.exchange((req, res) -> {
                byte[] raw = res.getBody().readAllBytes();
                return new Response(res.getStatusCode().value(), parse(raw), res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            });
        } catch (RestClientException e) {
            throw new ConnectorException(transportError(e));
        }
    }

    /** The credential {@code name} of the connection, or AUTH {@code credentials-missing} before any call. */
    public static String credential(ConnectionCredentials credentials, String name) {
        String value = credentials.values().get(name);
        if (value == null) {
            throw new ConnectorException(ConnectorError.of(ErrorKind.AUTH, "credentials-missing",
                    "La conexión no tiene la credencial " + name));
        }
        return value;
    }

    /** 401/403 → AUTH, 408/5xx → TRANSIENT (524 → UNCERTAIN), 429 → RATE_LIMITED; null for the rest. */
    public static ConnectorError commonError(Response response, String code) {
        int status = response.status();
        ErrorKind kind;
        if (status == 401 || status == 403) {
            kind = ErrorKind.AUTH;
        } else if (status == 429) {
            kind = ErrorKind.RATE_LIMITED;
        } else if (status == 524) {
            kind = ErrorKind.UNCERTAIN;
        } else if (status == 408 || status >= 500) {
            kind = ErrorKind.TRANSIENT;
        } else {
            return null;
        }
        return error(kind, code, response);
    }

    /** Remote failures keep only code, kind and HTTP status: the remote free text can carry PII. */
    public static ConnectorError error(ErrorKind kind, String code, Response response) {
        return new ConnectorError(kind, code, ErrorMessages.describe(code), response.status(), retryAfter(response.retryAfter()));
    }

    /** Text of {@code field}, joined when it is an array (NestJS validation messages). */
    public static String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isArray()) {
            StringBuilder joined = new StringBuilder();
            node.forEach(item -> joined.append(joined.isEmpty() ? "" : "; ").append(item.asString()));
            return joined.toString();
        }
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private JsonNode parse(byte[] raw) {
        if (raw.length == 0) {
            return MissingNode.getInstance();
        }
        try {
            return json.readTree(raw);
        } catch (RuntimeException e) {
            return MissingNode.getInstance();
        }
    }

    private static ConnectorError transportError(RestClientException e) {
        Throwable cause = e.getCause();
        boolean neverConnected = cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException
                || cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException;
        if (neverConnected) {
            return ConnectorError.of(ErrorKind.TRANSIENT, "connect-failed", cause.getClass().getSimpleName());
        }
        String what = cause instanceof IOException ? cause.getClass().getSimpleName() : e.getClass().getSimpleName();
        return ConnectorError.of(ErrorKind.UNCERTAIN, "no-response", "Sin respuesta tras enviar: " + what);
    }

    private static Duration retryAfter(String header) {
        return header != null && header.matches("\\d{1,6}") ? Duration.ofSeconds(Long.parseLong(header)) : null;
    }

    /** Builds a JSON object keeping insertion order and dropping null members (the POS forbids unknown/empty extras). */
    public static Map<String, Object> object(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
