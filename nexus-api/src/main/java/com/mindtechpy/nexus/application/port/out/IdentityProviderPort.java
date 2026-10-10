package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.Optional;
import java.util.UUID;

/**
 * Verifies console credentials. v1 implementation checks local users (bcrypt + lockout); an OIDC
 * provider would be another implementation without touching the use cases.
 */
public interface IdentityProviderPort {

    record Credentials(String email, String password) {
        @Override
        public String toString() {
            return "Credentials[email=" + email + ", password=***]";
        }
    }

    record AuthenticatedUser(UUID userId, String email, Role role, TenantId tenantId, boolean mustChangePassword) {
    }

    /** Empty when the credentials are wrong; throws {@code account-locked} while the account is locked. */
    Optional<AuthenticatedUser> authenticate(Credentials credentials);
}
