package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.IdentityProviderPort.AuthenticatedUser;

/** Result of login/refresh: the user to put in the access token and the new opaque refresh token. */
public record SessionGrant(AuthenticatedUser user, String refreshToken) {

    @Override
    public String toString() {
        return "SessionGrant[user=" + user.userId() + ", refreshToken=***]";
    }
}
