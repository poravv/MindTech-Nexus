package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.UUID;

public class GetCurrentUser {

    private final UserRepository users;

    public GetCurrentUser(UserRepository users) {
        this.users = users;
    }

    public User execute(Actor actor) {
        return users.findById(UUID.fromString(actor.id())).orElseThrow(() -> DomainException.notFound("Usuario"));
    }
}
