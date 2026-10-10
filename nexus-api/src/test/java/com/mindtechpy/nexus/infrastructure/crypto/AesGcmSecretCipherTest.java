package com.mindtechpy.nexus.infrastructure.crypto;

import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSecretCipherTest {

    private static final ConnectionCredentials CREDENTIALS =
            new ConnectionCredentials(Map.of("accessKey", "ak-123", "secretKey", "very-secret-value"));

    private final AesGcmSecretCipher cipher = new AesGcmSecretCipher(randomKey(), JsonMapper.builder().build());

    @Test
    void should_round_trip_when_aad_matches() {
        byte[] sealed = cipher.seal(CREDENTIALS, "tenant-a:conn-1");

        assertThat(cipher.open(sealed, "tenant-a:conn-1")).isEqualTo(CREDENTIALS);
        assertThat(new String(sealed, StandardCharsets.UTF_8)).doesNotContain("very-secret-value");
    }

    @Test
    void should_fail_when_aad_differs() {
        byte[] sealed = cipher.seal(CREDENTIALS, "tenant-a:conn-1");

        assertThatThrownBy(() -> cipher.open(sealed, "tenant-b:conn-1"))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "credentials-unreadable");
    }

    @Test
    void should_fail_when_ciphertext_is_tampered() {
        byte[] sealed = cipher.seal(CREDENTIALS, "tenant-a:conn-1");
        sealed[sealed.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.open(sealed, "tenant-a:conn-1")).isInstanceOf(DomainException.class);
    }

    @Test
    void should_fail_when_key_differs() {
        byte[] sealed = cipher.seal(CREDENTIALS, "tenant-a:conn-1");
        AesGcmSecretCipher otherKey = new AesGcmSecretCipher(randomKey(), JsonMapper.builder().build());

        assertThatThrownBy(() -> otherKey.open(sealed, "tenant-a:conn-1")).isInstanceOf(DomainException.class);
    }

    @Test
    void should_use_a_fresh_nonce_when_sealing_the_same_value_twice() {
        assertThat(cipher.seal(CREDENTIALS, "a:b")).isNotEqualTo(cipher.seal(CREDENTIALS, "a:b"));
    }

    @Test
    void should_reject_key_when_not_32_bytes() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new AesGcmSecretCipher(shortKey, JsonMapper.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
