package com.mindtechpy.nexus.infrastructure.connectors.gifthub;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * GiftHub webhook signature (manual §7.4): {@code v1=hex(HMAC-SHA256(secret, timestamp + "." + rawBody))}.
 * While GiftHub rotates the secret the header carries several comma-separated signatures; one valid
 * signature is enough. Timestamps further than {@value #TOLERANCE_SECONDS} s from now are replays.
 */
public final class GiftHubWebhookSignature {

    public static final long TOLERANCE_SECONDS = 300;
    private static final String PREFIX = "v1=";

    private GiftHubWebhookSignature() {
    }

    public static boolean isValid(String secret, String timestamp, String signatureHeader, byte[] rawBody, Instant now) {
        if (secret == null || timestamp == null || signatureHeader == null || !timestamp.matches("\\d{1,12}")) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - Long.parseLong(timestamp)) > TOLERANCE_SECONDS) {
            return false;
        }
        byte[] expected = hmac(secret, timestamp, rawBody);
        for (String part : signatureHeader.split(",")) {
            String candidate = part.strip();
            if (candidate.startsWith(PREFIX) && MessageDigest.isEqual(expected, decodeHex(candidate.substring(PREFIX.length())))) {
                return true;
            }
        }
        return false;
    }

    /** {@code v1=<hex>} for {@code timestamp} and {@code rawBody}; what GiftHub sends (also used by tests). */
    public static String sign(String secret, String timestamp, byte[] rawBody) {
        return PREFIX + HexFormat.of().formatHex(hmac(secret, timestamp, rawBody));
    }

    private static byte[] hmac(String secret, String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decodeHex(String hex) {
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
