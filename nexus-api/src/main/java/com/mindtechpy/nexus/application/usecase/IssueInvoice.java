package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.domain.fiscal.InvoiceRequest;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.List;
import java.util.Map;

/**
 * Persists the {@code ISSUE_INVOICE} operation of a validated canonical request, keyed by its
 * {@code sourceRef}, in the tenant's fiscal integration. Same {@code sourceRef} and payload returns the
 * existing operation; another payload is 409 {@code idempotency-conflict}.
 */
public class IssueInvoice {

    private final IntegrationRepository integrations;
    private final StartOperation startOperation;

    public IssueInvoice(IntegrationRepository integrations, StartOperation startOperation) {
        this.integrations = integrations;
        this.startOperation = startOperation;
    }

    /** @param payload {@code request} as JSON-compatible map: the stored payload and the hash input. */
    public StartOperation.Started execute(TenantId tenantId, InvoiceRequest request, Map<String, Object> payload,
                                          String correlationId) {
        Integration fiscal = fiscalIntegration(integrations, tenantId);
        return startOperation.execute(new StartOperation.Command(tenantId, fiscal.id(), OperationKind.ISSUE_INVOICE,
                request.sourceRef(), payload, correlationId));
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
