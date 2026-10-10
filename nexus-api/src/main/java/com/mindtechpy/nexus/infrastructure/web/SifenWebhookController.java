package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.ReceiveSifenEvent;
import com.mindtechpy.nexus.infrastructure.connectors.sifen.SifenHmacSigner;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * SIFEN state-change webhook (spec §5.4, §8.2), registered in SIFEN as
 * {@code https://nexus.mindtechpy.net/api/v1/webhooks/sifen/{connectionId}}. No token: the delivery is
 * authenticated with {@code X-Webhook-Signature} over the raw body and the connection's
 * {@code webhookSecret}, rejecting timestamps more than 300 s away. Bodies above 256 KB are refused unread.
 */
@Tag(name = "Webhooks · SIFEN")
@RestController
class SifenWebhookController {

    static final int MAX_BODY_BYTES = 256 * 1024;
    static final long TOLERANCE_SECONDS = 300;

    private final ReceiveSifenEvent receiveSifenEvent;
    private final JsonMapper json;

    SifenWebhookController(ReceiveSifenEvent receiveSifenEvent, JsonMapper json) {
        this.receiveSifenEvent = receiveSifenEvent;
        this.json = json;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Evento de cambio de estado de un DE (firma HMAC de SIFEN)")
    @PostMapping("/api/v1/webhooks/sifen/{connectionId}")
    ResponseEntity<Object> receive(@PathVariable UUID connectionId,
                                   @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestamp,
                                   @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
                                   HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return tooLarge();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return tooLarge();
        }
        ReceiveSifenEvent.Outcome outcome = receiveSifenEvent.execute(connectionId,
                secret -> isFresh(timestamp) && SifenHmacSigner.isValidWebhookSignature(secret, timestamp, body, signature),
                parse(body));
        return ResponseEntity.ok(Map.of("status", outcome.name()));
    }

    private ReceiveSifenEvent.Event parse(byte[] body) {
        try {
            return json.readValue(body, ReceiveSifenEvent.Event.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isFresh(String timestamp) {
        try {
            return Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) <= TOLERANCE_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static ResponseEntity<Object> tooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(ProblemResponses.problem(
                HttpStatus.CONTENT_TOO_LARGE, "payload-too-large", "El cuerpo supera " + MAX_BODY_BYTES / 1024 + " KB"));
    }
}
