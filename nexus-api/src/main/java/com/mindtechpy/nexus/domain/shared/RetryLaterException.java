package com.mindtechpy.nexus.domain.shared;

import java.time.Duration;

/** The request was not accepted because of load; the caller should retry after {@code retryAfter}. */
public class RetryLaterException extends DomainException {

    private final Duration retryAfter;

    public RetryLaterException(String code, String message, Duration retryAfter) {
        super(Kind.SATURATED, code, message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
