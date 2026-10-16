package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.TenantRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.integration.IntegrationPolicy;
import com.mindtechpy.nexus.domain.integration.IntegrationState;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates or reconfigures an integration (connections and policy). New integrations start ENABLED; the
 * state is changed only through {@link ChangeIntegrationState}. Connections must belong to the same tenant,
 * and an ORDER integration (GiftHub → POS) needs the CATALOG integration of that POS to that GiftHub.
 */
public class UpsertIntegration {

    public record Draft(String code, UUID sourceConnectionId, UUID targetConnectionId, IntegrationPolicy policy) {
    }

    private final IntegrationRepository integrations;
    private final ConnectionRepository connections;
    private final TenantRepository tenants;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public UpsertIntegration(IntegrationRepository integrations, ConnectionRepository connections, TenantRepository tenants,
                             AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        this.integrations = integrations;
        this.connections = connections;
        this.tenants = tenants;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    public Integration create(Actor actor, TenantId requestedTenant, Flow flow, Draft draft) {
        actor.require(Permission.MANAGE_INTEGRATIONS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        if (tenants.findById(tenantId).isEmpty()) {
            throw DomainException.notFound("Tenant");
        }
        Integration integration = Integration.create(tenantId, flow, draft.code(), draft.sourceConnectionId(),
                draft.targetConnectionId(), draft.policy(), clock.now());
        return tx.inTransaction(() -> {
            requireValid(integration);
            integrations.insert(integration);
            audit.append(AuditEvent.of(actor, tenantId, "integration.created", "integration", integration.id().toString(),
                    null, describe(integration), clock.now()));
            return integration;
        });
    }

    public Integration update(Actor actor, TenantId requestedTenant, UUID id, Draft draft) {
        actor.require(Permission.MANAGE_INTEGRATIONS);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        return tx.inTransaction(() -> {
            Integration current = integrations.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Integración"));
            Integration updated = current.reconfigured(draft.code(), draft.sourceConnectionId(), draft.targetConnectionId(),
                    draft.policy() == null ? current.policy() : draft.policy(), clock.now());
            requireValid(updated);
            integrations.update(updated);
            audit.append(AuditEvent.of(actor, tenantId, "integration.updated", "integration", id.toString(),
                    describe(current), describe(updated), clock.now()));
            return updated;
        });
    }

    private void requireValid(Integration integration) {
        requireConnection(integration.tenantId(), integration.sourceConnectionId());
        requireConnection(integration.tenantId(), integration.targetConnectionId());
        if (integrations.existsByCode(integration.tenantId(), integration.code(), integration.id())) {
            throw DomainException.conflict("integration-code-taken", "Ya existe una integración con ese código");
        }
        if (integration.flow() == Flow.CATALOG && integration.state() == IntegrationState.ENABLED && targetInUse(integration)) {
            throw DomainException.conflict("catalog-target-in-use",
                    "Ya hay una integración CATALOG habilitada hacia esa conexión GiftHub");
        }
        if (integration.flow() == Flow.ORDER && !hasMatchingCatalog(integration)) {
            throw DomainException.invalid("catalog-integration-missing", "Falta la integración CATALOG del mismo POS hacia "
                    + "esta conexión GiftHub: sin ella los pedidos llegan sin el código de producto del POS");
        }
    }

    /** Two enabled catalog integrations to one GiftHub would overlap their runs and archive each other's products. */
    private boolean targetInUse(Integration catalog) {
        return integrations.findAll(catalog.tenantId()).stream().anyMatch(other -> other.flow() == Flow.CATALOG
                && other.state() == IntegrationState.ENABLED && !other.id().equals(catalog.id())
                && other.targetConnectionId().equals(catalog.targetConnectionId()));
    }

    /** Orders carry the POS product codes only for products that a CATALOG integration pushed to that GiftHub. */
    private boolean hasMatchingCatalog(Integration order) {
        return integrations.findAll(order.tenantId()).stream().anyMatch(catalog -> catalog.flow() == Flow.CATALOG
                && catalog.targetConnectionId().equals(order.sourceConnectionId())
                && Objects.equals(catalog.sourceConnectionId(), order.targetConnectionId()));
    }

    private void requireConnection(TenantId tenantId, UUID connectionId) {
        if (connectionId != null && connections.findById(tenantId, connectionId).isEmpty()) {
            throw DomainException.notFound("Conexión");
        }
    }

    private static Map<String, Object> describe(Integration integration) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("flow", integration.flow().name());
        view.put("code", integration.code());
        view.put("sourceConnectionId", integration.sourceConnectionId() == null ? null : integration.sourceConnectionId().toString());
        view.put("targetConnectionId", integration.targetConnectionId().toString());
        view.put("state", integration.state().name());
        view.put("policy", integration.policy().toString());
        view.put("version", integration.version());
        return view;
    }
}
