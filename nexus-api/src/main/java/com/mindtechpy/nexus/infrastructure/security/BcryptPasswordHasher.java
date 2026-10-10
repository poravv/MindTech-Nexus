package com.mindtechpy.nexus.infrastructure.security;

import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.domain.identity.PasswordHash;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** bcrypt cost 12 for user passwords and client secrets (spec §5). */
@Component
class BcryptPasswordHasher implements PasswordHasherPort {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Override
    public PasswordHash hash(String raw) {
        return new PasswordHash(encoder.encode(raw));
    }

    @Override
    public boolean matches(String raw, PasswordHash hash) {
        return raw != null && encoder.matches(raw, hash.value());
    }
}
