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
import java.util.Base64;
import java.util.Map;

/**
 * AES-256-GCM with {@code NEXUS_MASTER_KEY} (ADR-006). Layout: {@code nonce(12) || ciphertext || tag(16)}.
 * The AAD ({@code tenant:connection}) is authenticated, so a row copied to another connection fails to open.
 */
public class AesGcmSecretCipher implements SecretCipherPort {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final TypeReference<Map<String, String>> CREDENTIALS = new TypeReference<>() {
    };

    private final SecretKeySpec key;
    private final JsonMapper json;
    private final SecureRandom random = new SecureRandom();

    /** @param base64Key 32 bytes encoded in base64. */
    public AesGcmSecretCipher(String base64Key, JsonMapper json) {
        byte[] raw = decodeKey(base64Key);
        if (raw.length != 32) {
            throw new IllegalArgumentException("NEXUS_MASTER_KEY debe ser de 32 bytes en base64");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.json = json;
    }

    @Override
    public byte[] seal(ConnectionCredentials credentials, String aad) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, aad);
            byte[] encrypted = cipher.doFinal(json.writeValueAsBytes(credentials.values()));
            return ByteBuffer.allocate(NONCE_BYTES + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar la credencial", e);
        }
    }

    @Override
    public ConnectionCredentials open(byte[] sealed, String aad) {
        if (sealed == null || sealed.length <= NONCE_BYTES) {
            throw unreadable();
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(sealed);
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            byte[] plain = cipher(Cipher.DECRYPT_MODE, nonce, aad).doFinal(encrypted);
            return new ConnectionCredentials(json.readValue(plain, CREDENTIALS));
        } catch (GeneralSecurityException e) {
            throw unreadable();
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static byte[] decodeKey(String base64Key) {
        try {
            return Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("NEXUS_MASTER_KEY no es base64 válido");
        }
    }

    private static DomainException unreadable() {
        return DomainException.invalid("credentials-unreadable", "No se pudieron descifrar las credenciales");
    }
}
