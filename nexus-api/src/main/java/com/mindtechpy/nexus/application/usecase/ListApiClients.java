package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ApiClientRepository;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.ApiClient;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;

public class ListApiClients {

    private final ApiClientRepository clients;

    public ListApiClients(ApiClientRepository clients) {
        this.clients = clients;
    }

    public List<ApiClient> execute(Actor actor, TenantId requestedTenant) {
        actor.require(Permission.READ_TENANT_DATA);
        return clients.findAll(actor.targetTenant(requestedTenant));
    }
}
