package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IdentityProviderPort.AuthenticatedUser;
import com.mindtechpy.nexus.application.port.out.SessionRepository;
import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.identity.UserSession;
import com.mindtechpy.nexus.domain.shared.DomainException;

/**
 * Exchanges a valid refresh token for a new access token. The refresh token rotates on every use and
 * the user is re-read, so role, status and password-change flag are always fresh. The rotation is a
 * conditional update on the presented token, so a token is honoured once even under concurrent refreshes.
 */
public class RefreshSession {

    private final SessionRepository sessions;
    private final UserRepository users;
    private final ClockPort clock;

    public RefreshSession(SessionRepository sessions, UserRepository users, ClockPort clock) {
        this.sessions = sessions;
        this.users = users;
        this.clock = clock;
    }

    public SessionGrant execute(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidSession();
        }
        String refreshHash = OpaqueTokens.sha256(refreshToken);
        UserSession session = sessions.findActive(refreshHash, clock.now())
                .orElseThrow(RefreshSession::invalidSession);
        User user = users.findById(session.userId()).filter(User::isActive).orElse(null);
        if (user == null || user.isLocked(clock.now())) {
            sessions.revoke(session.id(), clock.now());
            throw invalidSession();
        }
        String rotated = OpaqueTokens.generate(32);
        if (!sessions.rotateRefreshHash(refreshHash, OpaqueTokens.sha256(rotated), clock.now())) {
            throw invalidSession();
        }
        return new SessionGrant(new AuthenticatedUser(user.id(), user.email(), user.role(), user.tenantId(),
                user.mustChangePassword()), rotated);
    }

    private static DomainException invalidSession() {
        return DomainException.unauthorized("invalid-session", "Sesión inválida o expirada");
    }
}
