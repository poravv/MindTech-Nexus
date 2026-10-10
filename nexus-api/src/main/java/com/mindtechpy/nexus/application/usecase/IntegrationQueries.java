package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.integration.IntegrationHealth;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read side of integrations. An integration of another tenant is reported as not found (ADR-005). */
public class IntegrationQueries {

    /**
     * Health of one integration over the operations created since {@code from}. {@code dead} is its whole
     * current DLQ, regardless of age, as in the operations summary.
     */
    public record Health(UUID integrationId, IntegrationHealth health, Map<OperationStatus, Long> byStatus, long dead,
                         Instant lastOperationAt, OperationRepository.LastFailure lastFailure) {
    }

    private final IntegrationRepository integrations;
    private final OperationRepository operations;
    private final ClockPort clock;

    public IntegrationQueries(IntegrationRepository integrations, OperationRepository operations, ClockPort clock) {
        this.integrations = integrations;
        this.operations = operations;
        this.clock = clock;
    }

    public List<Integration> list(Actor actor, TenantId requestedTenant) {
        actor.require(Permission.READ_TENANT_DATA);
        return integrations.findAll(actor.targetTenant(requestedTenant));
    }

    public Integration get(Actor actor, TenantId requestedTenant, UUID id) {
        actor.require(Permission.READ_TENANT_DATA);
        return integrations.findById(actor.targetTenant(requestedTenant), id)
                .orElseThrow(() -> DomainException.notFound("Integración"));
    }

    /** One entry per integration of the tenant; {@code from} defaults to the last 24 hours. */
    public List<Health> health(Actor actor, TenantId requestedTenant, Instant from) {
        actor.require(Permission.READ_TENANT_DATA);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        Instant since = from != null ? from : clock.now().minus(Duration.ofHours(24));
        Map<UUID, Map<OperationStatus, Long>> byStatus = new HashMap<>();
        Map<UUID, Instant> lastOperationAt = new HashMap<>();
        for (OperationRepository.IntegrationStatusCount count : operations.countByIntegrationSince(tenantId, since)) {
            byStatus.computeIfAbsent(count.integrationId(), id -> new EnumMap<>(OperationStatus.class)).put(count.status(), count.count());
            lastOperationAt.merge(count.integrationId(), count.lastCreatedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        Map<UUID, Long> dead = operations.countDeadByIntegration(tenantId);
        Map<UUID, OperationRepository.LastFailure> lastFailure = operations.lastFailuresSince(tenantId, since);
        return integrations.findAll(tenantId).stream().map(integration -> {
            Map<OperationStatus, Long> recent = byStatus.getOrDefault(integration.id(), Map.of());
            return new Health(integration.id(), IntegrationHealth.of(integration.state(), recent), recent,
                    dead.getOrDefault(integration.id(), 0L), lastOperationAt.get(integration.id()), lastFailure.get(integration.id()));
        }).toList();
    }
}
