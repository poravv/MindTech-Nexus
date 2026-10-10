package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.SessionRepository;

/** Revokes the refresh session behind the given token. Idempotent: unknown tokens are ignored. */
public class Logout {

    private final SessionRepository sessions;
    private final ClockPort clock;

    public Logout(SessionRepository sessions, ClockPort clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    public void execute(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        sessions.findActive(OpaqueTokens.sha256(refreshToken), clock.now())
                .ifPresent(session -> sessions.revoke(session.id(), clock.now()));
    }
}
