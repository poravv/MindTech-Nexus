package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.RateLimiterPort;
import com.mindtechpy.nexus.application.usecase.ReceiveGiftHubEvent;
import com.mindtechpy.nexus.application.usecase.ReceiveGiftHubEvent.Delivery;
import com.mindtechpy.nexus.application.usecase.ReceiveGiftHubEvent.Received;
import com.mindtechpy.nexus.domain.integration.IntegrationPolicy;
import com.mindtechpy.nexus.domain.shared.RetryLaterException;
import com.mindtechpy.nexus.infrastructure.connectors.gifthub.GiftHubWebhookSignature;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Public endpoint registered as the GiftHub integration webhook (spec §5.4): authenticated by the HMAC of
 * the connection, not by a token. Body up to {@value #MAX_BODY_BYTES} bytes (413 beyond) and an in-memory
 * rate limit per connection (429 with {@code Retry-After}, which GiftHub retries). Answers fast: the order is
 * processed by the worker.
 */
@Tag(name = "Webhooks")
@RestController
class GiftHubWebhookController {

    static final int MAX_BODY_BYTES = 256 * 1024;
    private static final IntegrationPolicy PER_CONNECTION = new IntegrationPolicy(10.0, 50, 10, null, null, null, null, null);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    record ReceivedView(String outcome, UUID operationId) {
    }

    private final ReceiveGiftHubEvent receiveGiftHubEvent;
    private final RateLimiterPort rateLimiter;
    private final ClockPort clock;
    private final JsonMapper json;

    GiftHubWebhookController(ReceiveGiftHubEvent receiveGiftHubEvent, RateLimiterPort rateLimiter, ClockPort clock,
                             JsonMapper json) {
        this.receiveGiftHubEvent = receiveGiftHubEvent;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.json = json;
    }

    @Operation(summary = "Webhook de GiftHub (order.created, order.cancelled, ping); firma X-GiftHub-Signature")
    @PostMapping("/api/v1/webhooks/gifthub/{connectionId}")
    ResponseEntity<?> receive(@PathVariable UUID connectionId, HttpServletRequest request) throws IOException {
        if (!rateLimiter.tryAcquire(connectionId, PER_CONNECTION)) {
            throw new RetryLaterException("rate-limited", "Demasiados webhooks para esta conexión", Duration.ofSeconds(1));
        }
        try {
            byte[] raw = request.getContentLengthLong() > MAX_BODY_BYTES ? null
                    : request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (raw == null || raw.length > MAX_BODY_BYTES) {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(ProblemResponses.problem(
                        HttpStatus.CONTENT_TOO_LARGE, "payload-too-large", "El cuerpo supera " + MAX_BODY_BYTES + " bytes"));
            }
            String timestamp = request.getHeader("X-GiftHub-Timestamp");
            String signatures = request.getHeader("X-GiftHub-Signature");
            Received received = receiveGiftHubEvent.execute(new Delivery(connectionId, request.getHeader("X-GiftHub-Delivery"),
                    secret -> GiftHubWebhookSignature.isValid(secret, timestamp, signatures, raw, clock.now()), parse(raw)));
            return ResponseEntity.ok(new ReceivedView(received.outcome().name(), received.operationId()));
        } finally {
            rateLimiter.release(connectionId);
        }
    }

    private Map<String, Object> parse(byte[] raw) {
        try {
            return json.readValue(raw, MAP);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
