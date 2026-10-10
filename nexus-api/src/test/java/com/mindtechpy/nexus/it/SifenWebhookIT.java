package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** {@code POST /api/v1/webhooks/sifen/{connectionId}}: signature, freshness, dedupe and effect on TRACK. */
class SifenWebhookIT extends AbstractFiscalIT {

    @Test
    void should_make_the_waiting_track_run_now_and_finish_when_a_signed_event_arrives() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "PENDIENTE_ENVIO"));
        Operation op = issue(ref);
        drain();
        assertThat(nextRunAt(op.id())).isAfter(Instant.now().plusSeconds(30));
        stubFind(ref, document(ref, "APROBADO"));

        MvcResult result = deliver(sifenConnection, event(UUID.randomUUID().toString(), ref), now(), WEBHOOK_SECRET, 200);
        assertThat(body(result).get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(nextRunAt(op.id())).isBeforeOrEqualTo(Instant.now());
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(jdbc.sql("SELECT operation_id FROM inbound_events WHERE connection_id = :c")
                .param("c", sifenConnection).query(UUID.class).single()).isEqualTo(op.id());
    }

    @Test
    void should_answer_200_once_and_record_nothing_new_when_the_same_delivery_repeats() {
        String eventId = UUID.randomUUID().toString();
        String body = event(eventId, newSourceRef());
        long operationsBefore = operationsOfTenant();

        assertThat(body(deliver(sifenConnection, body, now(), WEBHOOK_SECRET, 200)).get("status").asString())
                .isEqualTo("ACCEPTED");
        assertThat(body(deliver(sifenConnection, body, now(), WEBHOOK_SECRET, 200)).get("status").asString())
                .isEqualTo("DUPLICATE");

        assertThat(jdbc.sql("SELECT count(*) FROM inbound_events WHERE connection_id = :c AND event_id = :e")
                .param("c", sifenConnection).param("e", eventId).query(Long.class).single()).isEqualTo(1);
        assertThat(operationsOfTenant()).isEqualTo(operationsBefore);
    }

    @Test
    void should_reject_with_401_and_record_nothing_when_the_signature_is_wrong_or_stale() {
        String body = event(UUID.randomUUID().toString(), newSourceRef());

        deliver(sifenConnection, body, now(), "otro-secreto", 401);
        deliver(sifenConnection, body, String.valueOf(Instant.now().minusSeconds(301).getEpochSecond()), WEBHOOK_SECRET, 401);
        perform(post("/api/v1/webhooks/sifen/{id}", sifenConnection).contentType(MediaType.APPLICATION_JSON).content(body), 401);

        assertThat(jdbc.sql("SELECT count(*) FROM inbound_events WHERE connection_id = :c").param("c", sifenConnection)
                .query(Long.class).single()).isZero();
    }

    @Test
    void should_answer_404_for_unknown_or_non_sifen_connections() {
        String body = event(UUID.randomUUID().toString(), newSourceRef());

        deliver(UUID.randomUUID(), body, now(), WEBHOOK_SECRET, 404);
        deliver(targetConnection, body, now(), WEBHOOK_SECRET, 404);
    }

    @Test
    void should_refuse_bodies_above_256_kb_with_413() {
        String body = "{\"eventId\":\"" + "x".repeat(256 * 1024) + "\"}";

        deliver(sifenConnection, body, now(), WEBHOOK_SECRET, 413);
    }

    private MvcResult deliver(UUID connectionId, String body, String timestamp, String secret, int expected) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = "sha256=" + hmac(secret, timestamp, bytes);
        return perform(post("/api/v1/webhooks/sifen/{id}", connectionId).contentType(MediaType.APPLICATION_JSON)
                .header("X-Webhook-Event", "APROBADO").header("X-Webhook-Timestamp", timestamp)
                .header("X-Webhook-Signature", signature).content(bytes), expected);
    }

    /** Signature built independently of the code under test (javax.crypto), as SIFEN's WebhookDispatcher does. */
    private static String hmac(String secret, String timestamp, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String event(String eventId, String transactionId) {
        return """
                {"eventId":"%s","evento":"APROBADO","emisorId":"EMISOR-1","timestamp":"2026-10-10T17:05:00Z",
                 "transactionId":"%s","cdc":"0180","estado":"APROBADO","codigoRespuesta":"0260","mensajeRespuesta":"ok"}"""
                .formatted(eventId, transactionId);
    }

    private static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private long operationsOfTenant() {
        return jdbc.sql("SELECT count(*) FROM operations WHERE tenant_id = :t").param("t", tenant).query(Long.class).single();
    }
}
