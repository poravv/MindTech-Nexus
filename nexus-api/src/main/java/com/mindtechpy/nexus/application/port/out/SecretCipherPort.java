package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;

import java.util.Optional;

/** Authenticated encryption of connection credentials, bound to {@code aad} (tenant:connection). */
public interface SecretCipherPort {

    byte[] seal(ConnectionCredentials credentials, String aad);

    /** Throws {@code credentials-unreadable} when the ciphertext or the AAD does not match. */
    ConnectionCredentials open(byte[] sealed, String aad);

    /**
     * Master-key rotation (ADR-006): empty when {@code sealed} already opens with the current key, otherwise the
     * same credentials sealed again with it. Throws {@code credentials-unreadable} when no configured key opens it.
     */
    Optional<byte[]> resealWithCurrentKey(byte[] sealed, String aad);
}
