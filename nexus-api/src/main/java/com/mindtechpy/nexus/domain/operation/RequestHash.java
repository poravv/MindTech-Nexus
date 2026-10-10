package com.mindtechpy.nexus.domain.operation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 of a canonical JSON form of a payload: keys sorted, null members dropped, numbers in plain
 * notation without trailing zeros. Two payloads that mean the same thing hash the same.
 */
public final class RequestHash {

    private RequestHash() {
    }

    public static String of(Map<String, ?> payload) {
        StringBuilder canonical = new StringBuilder();
        write(payload, canonical);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : new TreeMap<>(map).entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(entry.getKey().toString(), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            case Number number -> out.append(new BigDecimal(number.toString()).stripTrailingZeros().toPlainString());
            case Boolean bool -> out.append(bool);
            default -> writeString(value.toString(), out);
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        value.chars().forEach(c -> {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", c));
                    } else {
                        out.append((char) c);
                    }
                }
            }
        });
        out.append('"');
    }
}
