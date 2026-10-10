package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Permission;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.integration.IntegrationState;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * Creates {@code CATALOG_SYNC} operations: on demand from the console, and every {@code scheduleEverySeconds}
 * (default {@value #DEFAULT_EVERY_SECONDS}) for each ENABLED CATALOG integration. The scheduled key is the
 * start of the current window ({@code {integrationId}:{yyyyMMddHHmm}}), so calling {@link #scheduleDue} more
 * often than the window, or from a restarted worker, never creates a second run for the same window.
 */
public class RunCatalogSync {

    static final int DEFAULT_EVERY_SECONDS = 300;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final System.Logger log = System.getLogger(RunCatalogSync.class.getName());

    private final IntegrationRepository integrations;
    private final OperationRepository operations;
    private final StartOperation startOperation;
    private final AuditRepository audit;
    private final ClockPort clock;

    public RunCatalogSync(IntegrationRepository integrations, OperationRepository operations, StartOperation startOperation,
                          AuditRepository audit, ClockPort clock) {
        this.integrations = integrations;
        this.operations = operations;
        this.startOperation = startOperation;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Console "sync now": operators and up; 422 {@code flow-mismatch} unless the integration is CATALOG, 409
     * {@code catalog-run-in-progress} while another run to the same GiftHub is not finished.
     */
    public StartOperation.Started runNow(Actor actor, TenantId requestedTenant, UUID integrationId) {
        actor.require(Permission.CHANGE_INTEGRATION_STATE);
        TenantId tenantId = actor.targetTenant(requestedTenant);
        Integration integration = integrations.findById(tenantId, integrationId).orElseThrow(() -> DomainException.notFound("Integración"));
        if (integration.flow() == Flow.CATALOG && isRunning(integration)) {
            throw DomainException.conflict("catalog-run-in-progress",
                    "Ya hay una sincronización de catálogo en curso hacia este GiftHub; reintente cuando termine");
        }
        Instant now = clock.now();
        StartOperation.Started started = startOperation.execute(new StartOperation.Command(tenantId, integration.id(),
                OperationKind.CATALOG_SYNC, integration.id() + ":manual:" + SECOND.format(now), Map.of("trigger", "manual"), null));
        audit.append(AuditEvent.of(actor, tenantId, "integration.run", "integration", integration.id().toString(), null,
                Map.of("operationId", started.operation().id().toString()), now));
        return started;
    }

    /**
     * One run per window and ENABLED CATALOG integration, skipped while a run to the same GiftHub is not
     * finished; a failing integration does not stop the others.
     */
    public void scheduleDue() {
        Instant now = clock.now();
        for (Integration integration : integrations.findAllByFlow(Flow.CATALOG)) {
            if (integration.state() != IntegrationState.ENABLED || isRunning(integration)) {
                continue;
            }
            int every = integration.policy().scheduleEverySeconds() == null ? DEFAULT_EVERY_SECONDS
                    : integration.policy().scheduleEverySeconds();
            Instant window = Instant.ofEpochSecond(now.getEpochSecond() / every * every);
            try {
                StartOperation.Started started = startOperation.execute(new StartOperation.Command(integration.tenantId(),
                        integration.id(), OperationKind.CATALOG_SYNC, integration.id() + ":" + MINUTE.format(window),
                        Map.of("trigger", "schedule"), null));
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
     * Two runs to one GiftHub must not overlap: the older one would archive what the newer one just pushed. The
     * check is per target connection, not per integration, because two integrations can feed the same GiftHub.
     */
    private boolean isRunning(Integration integration) {
        return operations.hasUnfinished(integration.tenantId(), OperationKind.CATALOG_SYNC, integration.targetConnectionId());
    }
}
