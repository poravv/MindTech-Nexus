package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.operation.OperationStep;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read side of operations. An operation of another tenant is reported as not found (ADR-005). */
public class OperationQueries {

    public static final int MAX_PAGE_SIZE = 200;

    public record Detail(Operation operation, List<OperationStep> steps) {
    }

    /** Overview counters: by status and by flow since {@code from}; {@code dead} is the whole current DLQ. */
    public record Summary(Instant from, Map<OperationStatus, Long> byStatus, Map<Flow, Long> byFlow, long dead) {
    }

    private final OperationRepository operations;
    private final ClockPort clock;

    public OperationQueries(OperationRepository operations, ClockPort clock) {
        this.operations = operations;
        this.clock = clock;
    }

    /** Console listing, newest first; {@code page} is zero-based and {@code size} is capped at {@link #MAX_PAGE_SIZE}. */
    public List<Operation> list(Actor actor, TenantId requestedTenant, OperationRepository.Filter filter, int page, int size) {
        actor.require(Permission.READ_TENANT_DATA);
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return operations.find(actor.tenantFilter(requestedTenant), filter, limit, Math.max(page, 0) * limit);
    }

    public Detail detail(Actor actor, TenantId requestedTenant, UUID id) {
        actor.require(Permission.READ_TENANT_DATA);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        Operation operation = operations.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Operación"));
        return new Detail(operation, operations.findSteps(tenantId, id));
    }

    /** {@code from} defaults to the last 24 hours. */
    public Summary summary(Actor actor, TenantId requestedTenant, Instant from) {
        actor.require(Permission.READ_TENANT_DATA);
        Optional<TenantId> tenant = actor.tenantFilter(requestedTenant);
        Instant since = from != null ? from : clock.now().minus(Duration.ofHours(24));
        Map<OperationStatus, Long> byStatus = new EnumMap<>(OperationStatus.class);
        Map<Flow, Long> byFlow = new EnumMap<>(Flow.class);
        for (OperationRepository.StatusFlowCount count : operations.countSince(tenant, since)) {
            byStatus.merge(count.status(), count.count(), Long::sum);
            byFlow.merge(count.flow(), count.count(), Long::sum);
        }
        return new Summary(since, byStatus, byFlow, operations.countDead(tenant));
    }

    /** Runtime lookup for a machine client; the tenant comes from its token. */
    public Operation forClient(TenantId tenantId, UUID id) {
        return operations.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Operación"));
    }
}
