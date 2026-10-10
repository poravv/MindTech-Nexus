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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

/** Read side of operations. An operation of another tenant is reported as not found (ADR-005). */
public class OperationQueries {

    public static final int MAX_PAGE_SIZE = 200;
    /** Longest time series served: 288 buckets of 5 minutes fill a day, 366 daily ones a year. */
    public static final int MAX_POINTS = 500;
    private static final Map<String, Duration> BUCKETS = Map.of("PT5M", Duration.ofMinutes(5), "PT15M", Duration.ofMinutes(15),
            "PT1H", Duration.ofHours(1), "P1D", Duration.ofDays(1));

    public record Detail(Operation operation, List<OperationStep> steps) {
    }

    /** Overview counters: by status and by flow since {@code from}; {@code dead} is the whole current DLQ. */
    public record Summary(Instant from, Map<OperationStatus, Long> byStatus, Map<Flow, Long> byFlow, long dead) {
    }

    /** Operations created in the bucket starting at {@code t}, by status (absent statuses mean zero). */
    public record Point(Instant t, Map<OperationStatus, Long> byStatus) {
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

    /**
     * Operations created per {@code bucket} (PT5M, PT15M, PT1H or P1D) from the bucket that contains {@code from}
     * (default 24 hours ago) up to the current one, empty buckets included. Buckets are aligned to the Unix epoch
     * in UTC, so a P1D bucket is a UTC day.
     */
    public List<Point> timeseries(Actor actor, TenantId requestedTenant, Instant from, String bucket) {
        actor.require(Permission.READ_TENANT_DATA);
        Optional<TenantId> tenant = actor.tenantFilter(requestedTenant);
        Duration width = BUCKETS.get(bucket);
        if (width == null) {
            throw DomainException.invalid("invalid-bucket", "bucket debe ser PT5M, PT15M, PT1H o P1D");
        }
        Instant now = clock.now();
        Instant since = from != null ? from : now.minus(Duration.ofHours(24));
        if (since.isAfter(now)) {
            throw DomainException.invalid("invalid-from", "from no puede estar en el futuro");
        }
        Instant first = Instant.ofEpochSecond(Math.floorDiv(since.getEpochSecond(), width.toSeconds()) * width.toSeconds());
        long points = (now.getEpochSecond() - first.getEpochSecond()) / width.toSeconds() + 1;
        if (points > MAX_POINTS) {
            throw DomainException.invalid("too-many-points", "El rango pide " + points + " puntos; el máximo es " + MAX_POINTS);
        }
        Map<Instant, Map<OperationStatus, Long>> counts = new HashMap<>();
        for (OperationRepository.BucketCount count : operations.countByBucket(tenant, first, width)) {
            counts.computeIfAbsent(count.bucket(), t -> new EnumMap<>(OperationStatus.class)).put(count.status(), count.count());
        }
        return LongStream.range(0, points)
                .mapToObj(i -> first.plus(width.multipliedBy(i)))
                .map(t -> new Point(t, counts.getOrDefault(t, Map.of())))
                .toList();
    }

    /** Runtime lookup for a machine client; the tenant comes from its token. */
    public Operation forClient(TenantId tenantId, UUID id) {
        return operations.findById(tenantId, id).orElseThrow(() -> DomainException.notFound("Operación"));
    }
}
