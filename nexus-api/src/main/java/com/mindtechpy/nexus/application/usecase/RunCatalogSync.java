package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.integration.IntegrationPolicy;
import com.mindtechpy.nexus.domain.integration.IntegrationState;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * Creates {@code CATALOG_SYNC} operations: on demand from the console or the POS, and every {@code scheduleEverySeconds}
 * (default 300) for each ENABLED CATALOG integration. The scheduled key is the start of the current window
 * ({@code {integrationId}:{yyyyMMddHHmm}}), so calling {@link #scheduleDue} more often than the window, or from a
 * restarted worker, never creates a second run for the same window. After runs that ended DEAD in a row the next
 * one waits {@link IntegrationPolicy#catalogInterval} since the last one finished; the run history in
 * {@code operations} is the only state, so the first run that does not end DEAD resets the interval; that backoff
 * is the scheduler's alone: the console and the POS start a run whenever nothing is in flight, because someone just
 * changed the catalog. Every trigger checks "nothing in flight" and inserts its run under one lock per target
 * GiftHub ({@link OperationRepository#lockCatalogTarget}), so two triggers at once never start two runs.
 */
public class RunCatalogSync {

    /** Enough runs to reach the 24 h cap from the 60 s minimum interval (60·2^11 s). */
    private static final int BACKOFF_LOOKBACK = 11;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final System.Logger log = System.getLogger(RunCatalogSync.class.getName());

    private final IntegrationRepository integrations;
    private final OperationRepository operations;
    private final StartOperation startOperation;
    private final AuditRepository audit;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public RunCatalogSync(IntegrationRepository integrations, OperationRepository operations, StartOperation startOperation,
                          AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        this.integrations = integrations;
        this.operations = operations;
        this.startOperation = startOperation;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * Console "sync now": operators and up; 422 {@code flow-mismatch} unless the integration is CATALOG, 409
     * {@code catalog-run-in-progress} while another run to the same GiftHub is not finished.
     */
    public StartOperation.Started runNow(Actor actor, TenantId requestedTenant, UUID integrationId) {
        actor.require(Permission.CHANGE_INTEGRATION_STATE);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        Integration integration = integrations.findById(tenantId, integrationId).orElseThrow(() -> DomainException.notFound("Integración"));
        if (integration.flow() != Flow.CATALOG) {
            throw DomainException.invalid("flow-mismatch", "El tipo de operación no corresponde al flujo de la integración");
        }
        Instant now = clock.now();
        StartOperation.Started started = tx.inTransaction(() -> {
            lockAndRequireIdle(List.of(integration));
            return start(integration, integration.id() + ":manual:" + SECOND.format(now), "manual");
        });
        audit.append(AuditEvent.of(actor, tenantId, "integration.run", "integration", integration.id().toString(), null,
                Map.of("operationId", started.operation().id().toString()), now));
        return started;
    }

    /** What the POS gets back per integration: {@code created} is false when this minute's run already existed. */
    public record Requested(UUID integrationId, UUID operationId, boolean created) {
    }

    /**
     * The POS asks for an immediate sync (a product just sold out) for every ENABLED CATALOG integration of its
     * tenant. 404 {@code catalog-integration-not-found} if there is none; 409 {@code catalog-run-in-progress} if
     * any of them has a run in flight, before starting any. The key is per minute, so a repeated request within
     * the same minute is answered with the run already created.
     */
    public List<Requested> requestFromPos(TenantId tenantId) {
        List<Integration> enabled = integrations.findAll(tenantId).stream()
                .filter(integration -> integration.flow() == Flow.CATALOG && integration.state() == IntegrationState.ENABLED)
                .toList();
        if (enabled.isEmpty()) {
            throw new DomainException(DomainException.Kind.NOT_FOUND, "catalog-integration-not-found",
                    "El tenant no tiene una integración de catálogo habilitada");
        }
        String minute = MINUTE.format(clock.now());
        return tx.inTransaction(() -> {
            lockAndRequireIdle(enabled);
            return enabled.stream().map(integration -> {
                log.log(INFO, "[NEXUS-CATALOG] sync pedido por el POS tenant={0} integración={1}", tenantId, integration.code());
                StartOperation.Started started = start(integration, integration.id() + ":pos:" + minute, "pos");
                return new Requested(integration.id(), started.operation().id(), started.created());
            }).toList();
        });
    }

    /**
     * One run per window and ENABLED CATALOG integration, skipped while a run to the same GiftHub is not
     * finished; a failing integration does not stop the others.
     */
    public void scheduleDue() {
        Instant now = clock.now();
        for (Integration integration : integrations.findAllByFlow(Flow.CATALOG)) {
            if (integration.state() != IntegrationState.ENABLED || isRunning(integration) || isBackingOff(integration, now)) {
                continue;
            }
            long every = integration.policy().catalogInterval(0).toSeconds();
            Instant window = Instant.ofEpochSecond(now.getEpochSecond() / every * every);
            try {
                StartOperation.Started started = tx.inTransaction(() -> {
                    lockAndRequireIdle(List.of(integration));
                    return start(integration, integration.id() + ":" + MINUTE.format(window), "schedule");
                });
                if (started.created()) {
                    log.log(INFO, "[NEXUS-CATALOG] tenant={0} integración={1} corrida {2} op={3}", integration.tenantId(),
                            integration.code(), MINUTE.format(window), started.operation().id());
                }
            } catch (DomainException e) {
                log.log(WARNING, "[NEXUS-CATALOG] tenant={0} integración={1} no se programó: {2}", integration.tenantId(),
                        integration.code(), e.getMessage());
            }
        }
    }

    /**
     * 409 {@code catalog-run-in-progress} if any of them has a run in flight to its GiftHub. Must run inside the
     * transaction that inserts the run: the lock is released at commit, after the insert is visible. Locks are
     * taken in a fixed order so two requests over the same GiftHubs cannot deadlock.
     */
    private void lockAndRequireIdle(List<Integration> catalogIntegrations) {
        catalogIntegrations.stream().map(Integration::targetConnectionId).distinct().sorted()
                .forEach(operations::lockCatalogTarget);
        if (catalogIntegrations.stream().anyMatch(this::isRunning)) {
            throw DomainException.conflict("catalog-run-in-progress",
                    "Ya hay una sincronización de catálogo en curso hacia este GiftHub; reintente cuando termine");
        }
    }

    private StartOperation.Started start(Integration integration, String key, String trigger) {
        return startOperation.execute(new StartOperation.Command(integration.tenantId(), integration.id(),
                OperationKind.CATALOG_SYNC, key, Map.of("trigger", trigger), null));
    }

    /** True while the last runs ended DEAD in a row and their doubled interval has not passed since the last one. */
    private boolean isBackingOff(Integration integration, Instant now) {
        List<OperationRepository.FinishedRun> runs = operations.latestFinished(integration.tenantId(), integration.id(),
                OperationKind.CATALOG_SYNC, BACKOFF_LOOKBACK);
        int deadInARow = 0;
        while (deadInARow < runs.size() && isDead(runs.get(deadInARow).status())) {
            deadInARow++;
        }
        return deadInARow > 0
                && now.isBefore(runs.getFirst().finishedAt().plus(integration.policy().catalogInterval(deadInARow)));
    }

    /** A discarded run ended DEAD too: discarding it clears the DLQ, not the reason the target was unreachable. */
    private static boolean isDead(OperationStatus status) {
        return status == OperationStatus.DEAD || status == OperationStatus.DISCARDED;
    }

    /**
     * Two runs to one GiftHub must not overlap: the older one would archive what the newer one just pushed. The
     * check is per target connection, not per integration, because two integrations can feed the same GiftHub.
     */
    private boolean isRunning(Integration integration) {
        return operations.hasUnfinished(integration.tenantId(), OperationKind.CATALOG_SYNC, integration.targetConnectionId());
    }
}
