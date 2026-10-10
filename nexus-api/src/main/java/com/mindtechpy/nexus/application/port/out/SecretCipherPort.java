package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;

/** Authenticated encryption of connection credentials, bound to {@code aad} (tenant:connection). */
public interface SecretCipherPort {

    byte[] seal(ConnectionCredentials credentials, String aad);

    /** Throws {@code credentials-unreadable} when the ciphertext or the AAD does not match. */
    ConnectionCredentials open(byte[] sealed, String aad);
}
