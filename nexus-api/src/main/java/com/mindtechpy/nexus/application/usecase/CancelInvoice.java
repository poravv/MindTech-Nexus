package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.Map;

/**
 * Persists the {@code CANCEL_INVOICE} operation of an issued document, keyed by its {@code sourceRef}.
 * Fail-fast: the document must exist in this tenant (404) and its issue must have succeeded (409), so
 * nothing is sent to SIFEN for a document it never approved. Asking again with the same reason returns
 * the same operation, put back in the queue when it had FAILED ({@link IssueInvoice#retryIfFailed}).
 */
public class CancelInvoice {

    private final IntegrationRepository integrations;
    private final OperationRepository operations;
    private final StartOperation startOperation;
    private final ClockPort clock;

    public CancelInvoice(IntegrationRepository integrations, OperationRepository operations,
                         StartOperation startOperation, ClockPort clock) {
        this.integrations = integrations;
        this.operations = operations;
        this.startOperation = startOperation;
        this.clock = clock;
    }

    public StartOperation.Started execute(TenantId tenantId, String sourceRef, String reason, String correlationId) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw DomainException.invalid("invalid-reason", "reason es obligatorio (máximo 500 caracteres)");
        }
        Operation issue = operations.findByKey(tenantId, OperationKind.ISSUE_INVOICE, sourceRef)
                .orElseThrow(() -> DomainException.notFound("Factura"));
        if (issue.status() != OperationStatus.SUCCEEDED) {
            throw DomainException.conflict("invoice-not-issued",
                    "Solo se cancela un documento emitido; estado de la emisión: " + issue.status());
        }
        Integration fiscal = IssueInvoice.fiscalIntegration(integrations, tenantId);
        StartOperation.Started started = startOperation.execute(new StartOperation.Command(tenantId, fiscal.id(),
                OperationKind.CANCEL_INVOICE, sourceRef, Map.of("sourceRef", sourceRef, "reason", reason.strip()),
                correlationId));
        return IssueInvoice.retryIfFailed(started, operations, clock);
    }
}
