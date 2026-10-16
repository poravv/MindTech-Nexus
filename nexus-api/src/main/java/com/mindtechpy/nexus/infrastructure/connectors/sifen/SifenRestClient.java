package com.mindtechpy.nexus.infrastructure.connectors.sifen;

import com.mindtechpy.nexus.application.port.out.SifenPort;
import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;
import com.mindtechpy.nexus.domain.fiscal.InvoiceRequest;
import com.mindtechpy.nexus.domain.fiscal.SifenDocument;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.ErrorMessages;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * HTTP adapter of electronico-sifen. Every request carries {@code X-Access-Key}/{@code X-Secret-Key} and the
 * HMAC headers ({@code X-Timestamp}, {@code X-Nonce}, {@code X-Signature}) over the exact bytes sent, so it
 * works whether or not the key has {@code hmacRequired}. Connect timeout 5 s, read timeout from the
 * integration policy (KuDE 10 s), redirects never followed. Errors are classified per spec §7.4: a timeout
 * or 5xx after a write was sent is {@code UNCERTAIN}; on a read it is {@code TRANSIENT}.
 */
@Component
class SifenRestClient implements SifenPort {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration KUDE_TIMEOUT = Duration.ofSeconds(10);
    private static final String BUSINESS_RULE = "BUSINESS_RULE";
    private static final int MAX_DETAIL = 300;

    /** SIFEN error body ({@code ApiErrorBody}). */
    record ErrorBody(String code, String message) {
    }

    private record Call(String operation, String method, String pathWithQuery, byte[] body, Duration timeout,
                        String accept) {
        boolean isWrite() {
            return method.equals("POST");
        }
    }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json;
    private final MeterRegistry meters;

    SifenRestClient(JsonMapper json, MeterRegistry meters) {
        this.json = json;
        this.meters = meters;
    }

    @Override
    public Result<SifenDocument> submit(Endpoint sifen, Map<String, Object> invoice, Duration readTimeout) {
        InvoiceRequest request = json.convertValue(invoice, InvoiceRequest.class);
        byte[] body = json.writeValueAsBytes(SifenMapper.toFacturarRequest(request));
        return document(sifen, new Call("submit", "POST", "/api/v1/facturas", body, readTimeout, "application/json"));
    }

    @Override
    public Result<SifenDocument> find(Endpoint sifen, String transactionId, Duration readTimeout) {
        return document(sifen, new Call("find", "GET", "/api/v1/facturas/" + transactionId, new byte[0], readTimeout,
                "application/json"));
    }

    @Override
    public Result<SifenDocument> cancel(Endpoint sifen, String transactionId, String reason, Duration readTimeout) {
        byte[] body = json.writeValueAsBytes(Map.of("motivo", reason));
        return document(sifen, new Call("cancel", "POST", "/api/v1/facturas/" + transactionId + "/cancelar", body,
                readTimeout, "application/json"));
    }

    @Override
    public Result<byte[]> kude(Endpoint sifen, String cdc, String template) {
        String query = template == null ? "" : "?template=" + template;
        return execute(sifen, new Call("kude", "GET", "/api/v1/kude/" + cdc + query, new byte[0], KUDE_TIMEOUT,
                "application/pdf"), Ok::new);
    }

    private Result<SifenDocument> document(Endpoint sifen, Call call) {
        return execute(sifen, call, body -> new Ok<>(SifenMapper.toDocument(
                json.readValue(body, SifenMapper.FacturaResponse.class))));
    }

    private <T> Result<T> execute(Endpoint sifen, Call call, Function<byte[], Result<T>> onSuccess) {
        long started = System.nanoTime();
        Result<T> result;
        try {
            HttpResponse<byte[]> response = http.send(request(sifen, call), HttpResponse.BodyHandlers.ofByteArray());
            result = interpret(call, response, onSuccess);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            result = new Failed<>(ConnectorError.of(ErrorKind.TRANSIENT, "connect-failed", "SIFEN no aceptó la conexión"));
        } catch (HttpTimeoutException e) {
            result = new Failed<>(notAnswered(call, "read-timeout", "SIFEN no respondió en " + call.timeout().toSeconds() + " s"));
        } catch (IOException e) {
            result = new Failed<>(notAnswered(call, "io-error", "Error de red con SIFEN: " + e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = new Failed<>(notAnswered(call, "interrupted", "Llamada a SIFEN interrumpida"));
        }
        Timer.builder("nexus.connector.latency").tag("connector", "SIFEN").tag("operation", call.operation())
                .tag("outcome", outcome(result)).register(meters).record(Duration.ofNanos(System.nanoTime() - started));
        return result;
    }

    private HttpRequest request(Endpoint sifen, Call call) {
        URI uri = URI.create(sifen.connection().baseUrl() + call.pathWithQuery());
        String signedPath = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String accessKey = sifen.credentials().values().get("accessKey");
        String secretKey = sifen.credentials().values().get("secretKey");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(call.timeout())
                .header("Accept", call.accept())
                .header("X-Access-Key", accessKey)
                .header("X-Secret-Key", secretKey)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", SifenHmacSigner.sign(secretKey, call.method(), signedPath, timestamp, nonce, call.body()));
        if (call.isWrite()) {
            return builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(call.body())).build();
        }
        return builder.GET().build();
    }

    private <T> Result<T> interpret(Call call, HttpResponse<byte[]> response, Function<byte[], Result<T>> onSuccess) {
        int status = response.statusCode();
        if (status == 200) {
            try {
                return onSuccess.apply(response.body());
            } catch (RuntimeException e) {
                return new Failed<>(notAnswered(call, "invalid-response", "SIFEN respondió 200 con un cuerpo ilegible"));
            }
        }
        if (status == 404 && !call.operation().equals("submit")) {
            return new NotFound<>();
        }
        ErrorBody error = errorBody(response.body());
        String code = error.code() != null ? error.code() : "HTTP_" + status;
        Duration retryAfter = response.headers().firstValue("Retry-After").flatMap(SifenRestClient::seconds).orElse(null);
        return new Failed<>(new ConnectorError(classify(status, code, call.isWrite()), code, detail(code, error.message()), status, retryAfter));
    }

    /**
     * Only a {@code BUSINESS_RULE} keeps SIFEN's text: it names the issuer's own setup (timbrado, certificate,
     * cancellation window) and never the receptor, and the operator needs it to fix the cause. Validation
     * messages can echo the receptor's RUC or name, so every other code gets the fixed message.
     */
    private static String detail(String code, String message) {
        if (!BUSINESS_RULE.equals(code) || message == null) {
            return ErrorMessages.describe(code);
        }
        return message.length() <= MAX_DETAIL ? message : message.substring(0, MAX_DETAIL);
    }

    /**
     * Spec §7.4 for SIFEN: {@code EMISION_EN_CURSO} waits, {@code IDEMPOTENCY_CONFLICT} needs a human, other
     * 4xx are business refusals ({@code BUSINESS_RULE} is a 409, unknown branch/point 404). A 402 (plan limit,
     * suspended workspace) is not a refusal of the document: it waits DEAD for a replay once the plan is fixed.
     */
    static ErrorKind classify(int status, String code, boolean isWrite) {
        return switch (status) {
            case 401, 403 -> ErrorKind.AUTH;
            case 402 -> ErrorKind.PLAN_LIMIT;
            case 429 -> ErrorKind.RATE_LIMITED;
            case 409 -> switch (code) {
                case "EMISION_EN_CURSO" -> ErrorKind.IN_PROGRESS;
                case "IDEMPOTENCY_CONFLICT" -> ErrorKind.CONFLICT;
                default -> ErrorKind.REJECTED;
            };
            case 502, 503, 504 -> ErrorKind.TRANSIENT;
            default -> status >= 400 && status < 500 ? ErrorKind.REJECTED
                    : isWrite ? ErrorKind.UNCERTAIN : ErrorKind.TRANSIENT;
        };
    }

    /** No usable answer: after a write SIFEN may have applied it, after a read nothing changed. */
    private static ConnectorError notAnswered(Call call, String code, String detail) {
        return ConnectorError.of(call.isWrite() ? ErrorKind.UNCERTAIN : ErrorKind.TRANSIENT, code, detail);
    }

    private ErrorBody errorBody(byte[] body) {
        try {
            return json.readValue(body, ErrorBody.class);
        } catch (RuntimeException e) {
            return new ErrorBody(null, null);
        }
    }

    private static Optional<Duration> seconds(String value) {
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value.strip())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String outcome(Result<?> result) {
        return switch (result) {
            case Ok<?> ok -> "ok";
            case NotFound<?> notFound -> "not_found";
            case Failed<?> failed -> failed.error().kind().name().toLowerCase();
        };
    }
}
