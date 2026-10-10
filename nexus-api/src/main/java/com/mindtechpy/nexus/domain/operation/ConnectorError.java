package com.mindtechpy.nexus.domain.operation;

import java.time.Duration;
import java.util.Objects;

/**
 * Classified failure of a step. {@code detail} is shown in the console and runtime API, so it must not
 * contain secrets or full PII. {@code httpStatus} and {@code retryAfter} are optional.
 */
public record ConnectorError(ErrorKind kind, String code, String detail, Integer httpStatus, Duration retryAfter) {

    public ConnectorError {
        Objects.requireNonNull(kind, "kind");
        code = code == null || code.isBlank() ? kind.name() : code;
    }

    public static ConnectorError of(ErrorKind kind, String code, String detail) {
        return new ConnectorError(kind, code, detail, null, null);
    }
}
