package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.identity.PasswordHash;

public interface PasswordHasherPort {

    PasswordHash hash(String raw);

    boolean matches(String raw, PasswordHash hash);
}
