package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.domain.identity.ApiClient;

/** A client together with its plain secret, returned exactly once (create or rotate). */
public record IssuedSecret(ApiClient client, String secret) {

    @Override
    public String toString() {
        return "IssuedSecret[client=" + client.clientId() + ", secret=***]";
    }
}
