package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IdentityProviderPort;
import com.mindtechpy.nexus.application.port.out.IdentityProviderPort.AuthenticatedUser;
import com.mindtechpy.nexus.application.port.out.IdentityProviderPort.Credentials;
import com.mindtechpy.nexus.application.port.out.SessionRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.UserSession;
import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.Map;

/** Console login: verifies credentials and opens a revocable refresh session. */
public class LoginUser {

    private final IdentityProviderPort identityProvider;
    private final SessionRepository sessions;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public LoginUser(IdentityProviderPort identityProvider, SessionRepository sessions, AuditRepository audit,
                     ClockPort clock, TransactionRunner tx) {
        this.identityProvider = identityProvider;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public SessionGrant execute(String email, String password, String ip, String userAgent) {
        AuthenticatedUser user = identityProvider.authenticate(new Credentials(email, password))
                .orElseThrow(() -> DomainException.unauthorized("invalid-credentials", "Email o contraseña incorrectos"));
        String refreshToken = OpaqueTokens.generate(32);
        UserSession session = UserSession.open(user.userId(), OpaqueTokens.sha256(refreshToken), clock.now(), ip, userAgent);
        tx.inTransaction(() -> {
            sessions.insert(session);
            audit.append(AuditEvent.of(Actor.user(user.userId().toString(), user.role(), user.tenantId()), user.tenantId(),
                    "auth.login", "user", user.userId().toString(), null, Map.of("sessionId", session.id().toString()),
                    clock.now()));
            return session;
        });
        return new SessionGrant(user, refreshToken);
    }
}
