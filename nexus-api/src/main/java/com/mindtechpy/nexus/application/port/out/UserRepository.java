package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Users are looked up globally by email (login) and by id (token subject); listings are tenant-filtered. */
public interface UserRepository {

    void insert(User user);

    void update(User user);

    Optional<User> findByEmail(String normalizedEmail);

    Optional<User> findById(UUID id);

    /** Empty filter means every user (platform_admin only). */
    List<User> findAll(Optional<TenantId> tenantId);

    long count();
}
