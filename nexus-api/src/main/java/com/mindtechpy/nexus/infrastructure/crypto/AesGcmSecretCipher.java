package com.mindtechpy.nexus.infrastructure.crypto;

import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.shared.DomainException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * AES-256-GCM with {@code NEXUS_MASTER_KEY} (ADR-006). Layout: {@code nonce(12) || ciphertext || tag(16)}.
 * The AAD ({@code tenant:connection}) is authenticated, so a row copied to another connection fails to open.
 * During a key rotation {@code NEXUS_MASTER_KEY_PREVIOUS} also opens what was sealed before it; sealing always
 * uses the current key.
 */
public class AesGcmSecretCipher implements SecretCipherPort {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final TypeReference<Map<String, String>> CREDENTIALS = new TypeReference<>() {
    };

    private final SecretKeySpec key;
    private final SecretKeySpec previousKey;
    private final JsonMapper json;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param base64Key         32 bytes encoded in base64
     * @param base64PreviousKey the key being rotated out, same format; null or blank when there is none
     */
    public AesGcmSecretCipher(String base64Key, String base64PreviousKey, JsonMapper json) {
        this.key = aesKey(base64Key, "NEXUS_MASTER_KEY");
        this.previousKey = base64PreviousKey == null || base64PreviousKey.isBlank() ? null
                : aesKey(base64PreviousKey, "NEXUS_MASTER_KEY_PREVIOUS");
        this.json = json;
    }

    @Override
    public byte[] seal(ConnectionCredentials credentials, String aad) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad);
            byte[] encrypted = cipher.doFinal(json.writeValueAsBytes(credentials.values()));
            return ByteBuffer.allocate(NONCE_BYTES + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar la credencial", e);
        }
    }

    @Override
    public ConnectionCredentials open(byte[] sealed, String aad) {
        byte[] plain = decrypt(sealed, aad, key);
        if (plain == null && previousKey != null) {
            plain = decrypt(sealed, aad, previousKey);
        }
        if (plain == null) {
            throw unreadable();
        }
        return new ConnectionCredentials(json.readValue(plain, CREDENTIALS));
    }

    @Override
    public Optional<byte[]> resealWithCurrentKey(byte[] sealed, String aad) {
        if (decrypt(sealed, aad, key) != null) {
            return Optional.empty();
        }
        return Optional.of(seal(open(sealed, aad), aad));
    }

    /** Plain bytes, or null when {@code sealed} does not open with {@code with} and {@code aad}. */
    private static byte[] decrypt(byte[] sealed, String aad, SecretKeySpec with) {
        if (sealed == null || sealed.length <= NONCE_BYTES) {
            return null;
        }
        try {
            return cipher(Cipher.DECRYPT_MODE, with, Arrays.copyOf(sealed, NONCE_BYTES), aad)
                    .doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static Cipher cipher(int mode, SecretKeySpec with, byte[] nonce, String aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, with, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static SecretKeySpec aesKey(String base64Key, String name) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " no es base64 válido");
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException(name + " debe ser de 32 bytes en base64");
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static DomainException unreadable() {
        return DomainException.invalid("credentials-unreadable", "No se pudieron descifrar las credenciales");
    }
}
