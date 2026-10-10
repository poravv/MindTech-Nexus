package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.TenantRepository;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.tenant.Tenant;

import java.util.List;

public class ListTenants {

    private final TenantRepository tenants;

    public ListTenants(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public List<Tenant> execute(Actor actor) {
        actor.require(Permission.MANAGE_TENANTS);
        return tenants.findAll();
    }
}
