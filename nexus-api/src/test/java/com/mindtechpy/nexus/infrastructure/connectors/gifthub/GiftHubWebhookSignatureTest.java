package com.mindtechpy.nexus.infrastructure.connectors.gifthub;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Vector produced with Node crypto exactly like GiftHub {@code signWebhook} ({@code webhook-signature.ts}). */
class GiftHubWebhookSignatureTest {

    private static final String SECRET = "secreto";
    private static final String TIMESTAMP = "1791470580";
    private static final byte[] BODY = "{\"id\":\"4b7e\",\"type\":\"ping\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
    private static final String EXPECTED = "v1=a614a81142f37180ed50f2b39db379b022aacaa7cd513c8b8d187360052646fd";
    private static final Instant NOW = Instant.ofEpochSecond(1791470580);

    @Test
    void should_sign_like_gifthub() {
        assertThat(GiftHubWebhookSignature.sign(SECRET, TIMESTAMP, BODY)).isEqualTo(EXPECTED);
    }

    @Test
    void should_accept_when_any_signature_of_the_rotation_header_matches() {
        assertThat(GiftHubWebhookSignature.isValid(SECRET, TIMESTAMP, "v1=" + "0".repeat(64) + "," + EXPECTED, BODY, NOW)).isTrue();
    }

    @Test
    void should_reject_tampered_body_wrong_secret_or_missing_prefix() {
        assertThat(GiftHubWebhookSignature.isValid(SECRET, TIMESTAMP, EXPECTED, "{}".getBytes(StandardCharsets.UTF_8), NOW)).isFalse();
        assertThat(GiftHubWebhookSignature.isValid("otro", TIMESTAMP, EXPECTED, BODY, NOW)).isFalse();
        assertThat(GiftHubWebhookSignature.isValid(SECRET, TIMESTAMP, EXPECTED.substring(3), BODY, NOW)).isFalse();
        assertThat(GiftHubWebhookSignature.isValid(SECRET, TIMESTAMP, "v1=zz", BODY, NOW)).isFalse();
    }

    @Test
    void should_reject_timestamps_outside_five_minutes() {
        assertThat(GiftHubWebhookSignature.isValid(SECRET, TIMESTAMP, EXPECTED, BODY, NOW.plusSeconds(300))).isTrue();
        assertThat(GiftHubWebhookSignature.isValid(SECRET, TIMESTAMP, EXPECTED, BODY, NOW.plusSeconds(301))).isFalse();
        assertThat(GiftHubWebhookSignature.isValid(SECRET, TIMESTAMP, EXPECTED, BODY, NOW.minusSeconds(301))).isFalse();
        assertThat(GiftHubWebhookSignature.isValid(SECRET, "abc", EXPECTED, BODY, NOW)).isFalse();
    }
}
