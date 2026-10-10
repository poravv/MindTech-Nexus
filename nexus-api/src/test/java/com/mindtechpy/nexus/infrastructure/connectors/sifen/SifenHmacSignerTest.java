package com.mindtechpy.nexus.infrastructure.connectors.sifen;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Request vectors published in {@code electronico-sifen-backend/docs/INTEGRACION_API.md} (HMAC section). */
class SifenHmacSignerTest {

    private static final String SECRET_KEY = "sk_test_4f8b2c1e9d7a6b5c3e2f1a0b9c8d7e6f";

    @Test
    void should_match_sifen_vector_when_signing_a_post_with_body() {
        byte[] body = "{\"transactionId\":\"POS-01:000123\",\"fechaEmision\":\"2026-09-23T10:30:00-03:00\"}"
                .getBytes(StandardCharsets.UTF_8);

        String signature = SifenHmacSigner.sign(SECRET_KEY, "POST", "/api/v1/facturas", "1790000000",
                "3f2b8c1e-7a4d-4e9b-9c2f-1a2b3c4d5e6f", body);

        assertThat(signature).isEqualTo("334a2274fed62303c43d6689530b64b64995d338addb95412aafb5027abcd9fc");
    }

    @Test
    void should_match_sifen_vector_when_signing_a_get_with_query_and_no_body() {
        String signature = SifenHmacSigner.sign(SECRET_KEY, "GET", "/api/v1/facturas?page=0&size=20", "1790000000",
                "9c1a2b3d-4e5f-4061-8a2b-1c2d3e4f5061", new byte[0]);

        assertThat(signature).isEqualTo("dfea3f63b6e2393f26221fb5fad4a6bdc27d60d81573a30ae5cca5fbb41c266c");
    }

    @Test
    void should_accept_only_the_exact_webhook_signature_of_timestamp_dot_body() {
        byte[] body = ("{\"eventId\":\"0b7f9d6e-3c1a-4f2e-8d5b-6a4c2e1f0b9d\",\"evento\":\"APROBADO\","
                + "\"transactionId\":\"MI-SISTEMA-01:POS:000001\"}").getBytes(StandardCharsets.UTF_8);
        String signature = "sha256=6072f74fc41cd010c32a66a020b388b7e93ba94633b91566a66d42c3084b4386";

        assertThat(SifenHmacSigner.isValidWebhookSignature("whsec_test_5f1e", "1790000000", body, signature)).isTrue();
        assertThat(SifenHmacSigner.isValidWebhookSignature("whsec_test_5f1e", "1790000001", body, signature)).isFalse();
        assertThat(SifenHmacSigner.isValidWebhookSignature("otro-secreto", "1790000000", body, signature)).isFalse();
        assertThat(SifenHmacSigner.isValidWebhookSignature("whsec_test_5f1e", "1790000000", body, null)).isFalse();
    }
}
