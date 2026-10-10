package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;

public class ListUsers {

    private final UserRepository users;

    public ListUsers(UserRepository users) {
        this.users = users;
    }

    public List<User> execute(Actor actor, TenantId requestedTenant) {
        actor.require(Permission.MANAGE_USERS);
        return users.findAll(actor.tenantFilter(requestedTenant));
    }
}
