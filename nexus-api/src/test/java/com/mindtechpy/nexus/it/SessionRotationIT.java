package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.application.port.out.SessionRepository;
import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.identity.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Refresh rotation is a conditional update: a refresh token is honoured once, even by two concurrent refreshes. */
class SessionRotationIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessions;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordHasherPort hasher;

    @Test
    void should_rotate_a_refresh_hash_only_once_when_two_refreshes_present_the_same_token() {
        User user = User.create(null, "rotation-" + UUID.randomUUID() + "@nexus.test", hasher.hash(PASSWORD), Role.PLATFORM_ADMIN);
        users.insert(user);
        Instant now = Instant.now();
        sessions.insert(UserSession.open(user.id(), "hash-old-" + user.id(), now, "10.0.0.9", "it"));

        boolean first = sessions.rotateRefreshHash("hash-old-" + user.id(), "hash-a-" + user.id(), now);
        boolean second = sessions.rotateRefreshHash("hash-old-" + user.id(), "hash-b-" + user.id(), now);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(sessions.findActive("hash-a-" + user.id(), now)).isPresent();
        assertThat(sessions.findActive("hash-b-" + user.id(), now)).isEmpty();
    }

    @Test
    void should_not_rotate_an_expired_session() {
        User user = User.create(null, "rotation-" + UUID.randomUUID() + "@nexus.test", hasher.hash(PASSWORD), Role.PLATFORM_ADMIN);
        users.insert(user);
        Instant openedLongAgo = Instant.now().minus(UserSession.LIFETIME).minusSeconds(60);
        sessions.insert(UserSession.open(user.id(), "hash-expired-" + user.id(), openedLongAgo, "10.0.0.9", "it"));

        assertThat(sessions.rotateRefreshHash("hash-expired-" + user.id(), "hash-new-" + user.id(), Instant.now())).isFalse();
    }
}
