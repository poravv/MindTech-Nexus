package com.mindtechpy.nexus.infrastructure.connectors.sifen;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The two HMAC-SHA256 schemes of electronico-sifen ({@code docs/INTEGRACION_API.md}): request signing
 * with the API secret key, and webhook delivery signatures with the webhook secret.
 */
public final class SifenHmacSigner {

    private SifenHmacSigner() {
    }

    /**
     * {@code X-Signature}: hex of HMAC(secretKey, {@code METHOD\nPATH[?QUERY]\nTIMESTAMP\nNONCE\nhex(body)}).
     *
     * @param pathWithQuery raw path plus {@code ?query} exactly as it travels; no {@code ?} without query
     * @param body          exact bytes sent; empty for GET
     */
    public static String sign(String secretKey, String method, String pathWithQuery, String timestamp, String nonce,
                              byte[] body) {
        String canonical = method + "\n" + pathWithQuery + "\n" + timestamp + "\n" + nonce + "\n"
                + HexFormat.of().formatHex(body);
        return hmacHex(secretKey, canonical.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code X-Webhook-Signature}: {@code sha256=} + hex of HMAC(secret, {@code timestamp + "." + body}). */
    public static boolean isValidWebhookSignature(String secret, String timestamp, byte[] body, String signature) {
        if (timestamp == null || signature == null) {
            return false;
        }
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        String expected = "sha256=" + hmacHex(secret, signed);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
