package com.mindtechpy.nexus.domain.identity;

import java.util.Objects;

/** A one-way hash of a password or client secret. Never holds the raw value. */
public record PasswordHash(String value) {

    public PasswordHash {
        Objects.requireNonNull(value, "hash");
    }

    @Override
    public String toString() {
        return "PasswordHash[***]";
    }
}
