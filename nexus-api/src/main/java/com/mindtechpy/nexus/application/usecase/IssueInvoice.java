package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.domain.fiscal.InvoiceRequest;
import com.mindtechpy.nexus.domain.fiscal.ProviderStatus;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;
import java.util.Map;

import static java.lang.System.Logger.Level.INFO;

/**
 * Persists the {@code ISSUE_INVOICE} operation of a validated canonical request, keyed by its
 * {@code sourceRef}, in the tenant's fiscal integration. Same {@code sourceRef} and payload returns the
 * existing operation, or a new attempt of it when it failed without effect ({@link #retryIfFailed}); another
 * payload is 409 {@code idempotency-conflict}.
 */
public class IssueInvoice {

    private static final System.Logger log = System.getLogger(IssueInvoice.class.getName());

    private final IntegrationRepository integrations;
    private final OperationRepository operations;
    private final StartOperation startOperation;
    private final ClockPort clock;

    public IssueInvoice(IntegrationRepository integrations, OperationRepository operations,
                        StartOperation startOperation, ClockPort clock) {
        this.integrations = integrations;
        this.operations = operations;
        this.startOperation = startOperation;
        this.clock = clock;
    }

    /** @param payload {@code request} as JSON-compatible map: the stored payload and the hash input. */
    public StartOperation.Started execute(TenantId tenantId, InvoiceRequest request, Map<String, Object> payload,
                                          String correlationId) {
        Integration fiscal = fiscalIntegration(integrations, tenantId);
        StartOperation.Started started = startOperation.execute(new StartOperation.Command(tenantId, fiscal.id(),
                OperationKind.ISSUE_INVOICE, request.sourceRef(), payload, correlationId));
        return retryIfFailed(started, operations, clock);
    }

    /**
     * Idempotency prevents duplicate effects, not a new attempt after a failure without effect. A FAILED
     * operation goes back to the queue (same id, key and payload) unless SIFEN processed and rejected the
     * document ({@code RECHAZADO}: retrying cannot change it); its saga verifies against SIFEN before sending
     * again, so a document that does exist is adopted, never duplicated. DEAD (waits for a human replay),
     * in-flight and succeeded operations are returned as they are.
     */
    static StartOperation.Started retryIfFailed(StartOperation.Started started, OperationRepository operations,
                                                ClockPort clock) {
        Operation failed = started.operation();
        if (started.created() || failed.status() != OperationStatus.FAILED
                || ProviderStatus.isRejection(failed.lastError())) {
            return started;
        }
        Operation retried = failed.retriedAfterFailure(clock.now());
        if (!operations.update(failed, retried)) {
            return new StartOperation.Started(operations.findById(failed.tenantId(), failed.id()).orElseThrow(), false);
        }
        log.log(INFO, "{0} op={1} tenant={2} kind={3} FAILED reintentada por el cliente: vuelve a la cola en {4}",
                failed.flow().logPrefix(), failed.id(), failed.tenantId(), failed.kind(), failed.currentStep());
        return new StartOperation.Started(retried, true);
    }

    /** The tenant's only FISCAL integration: invoices carry no integration id, so more than one is ambiguous. */
    static Integration fiscalIntegration(IntegrationRepository integrations, TenantId tenantId) {
        List<Integration> fiscal = integrations.findAll(tenantId).stream()
                .filter(integration -> integration.flow() == Flow.FISCAL).toList();
        if (fiscal.isEmpty()) {
            throw DomainException.conflict("fiscal-integration-missing", "El tenant no tiene una integración FISCAL");
        }
        if (fiscal.size() > 1) {
            throw DomainException.conflict("fiscal-integration-ambiguous", "El tenant tiene más de una integración FISCAL");
        }
        return fiscal.getFirst();
    }
}
