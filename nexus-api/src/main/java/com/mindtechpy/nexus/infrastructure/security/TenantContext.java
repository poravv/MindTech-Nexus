package com.mindtechpy.nexus.infrastructure.security;

import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.UUID;

/** Builds the {@link Actor} from the validated JWT. The tenant comes only from the {@code tenant_id} claim. */
public final class TenantContext {

    private TenantContext() {
    }

    /** Current console user; refuses while the user still has to change the bootstrap password. */
    public static Actor currentUser() {
        Jwt jwt = currentJwt();
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("must_change_password"))) {
            throw DomainException.forbidden("password-change-required", "Debe cambiar la contraseña antes de continuar");
        }
        return toActor(jwt);
    }

    /** Current console user, allowed even before the mandatory password change (me, password). */
    public static Actor currentUserAllowingPasswordChange() {
        return toActor(currentJwt());
    }

    /** Current machine client; 403 {@code insufficient-scope} unless its token carries {@code required}. */
    public static Actor currentClient(Scope required) {
        Jwt jwt = currentJwt();
        String scopes = jwt.getClaimAsString("scope");
        if (scopes == null || !Arrays.asList(scopes.split(" ")).contains(required.value())) {
            throw DomainException.forbidden("insufficient-scope", "El token no tiene el scope " + required.value());
        }
        return Actor.client(jwt.getSubject(), new TenantId(UUID.fromString(jwt.getClaimAsString("tenant_id"))));
    }

    private static Actor toActor(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant_id");
        return Actor.user(jwt.getSubject(), Role.fromValue(jwt.getClaimAsString("role")),
                tenant == null ? null : new TenantId(UUID.fromString(tenant)));
    }

    private static Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw DomainException.unauthorized("unauthenticated", "Autenticación requerida");
        }
        return jwt;
    }
}
