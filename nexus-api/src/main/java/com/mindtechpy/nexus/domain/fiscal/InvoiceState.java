package com.mindtechpy.nexus.domain.fiscal;

import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical view of a fiscal document (spec §8.1), derived from its {@code ISSUE_INVOICE} operation and,
 * once a cancellation succeeded, its {@code CANCEL_INVOICE} operation.
 */
public record InvoiceState(String sourceRef, UUID operationId, InvoiceStatus status, String cdc, String documentNumber,
                           String providerStatus, String providerCode, String providerMessage, Long total,
                           Instant updatedAt, ConnectorError error) {

    public static InvoiceState of(Operation issue, Operation cancel) {
        Map<String, Object> result = issue.result() == null ? Map.of() : issue.result();
        String cdc = (String) result.get("cdc");
        String documentNumber = (String) result.get("documentNumber");
        Long total = result.get("total") instanceof Number number ? number.longValue() : null;
        if (cancel != null && cancel.status() == OperationStatus.SUCCEEDED) {
            return new InvoiceState(issue.idempotencyKey(), issue.id(), InvoiceStatus.CANCELLED, cdc, documentNumber,
                    ProviderStatus.CANCELADO.name(), null, null, total, cancel.updatedAt(), null);
        }
        ConnectorError error = issue.lastError();
        if (ProviderStatus.isRejection(error)) {
            return new InvoiceState(issue.idempotencyKey(), issue.id(), InvoiceStatus.REJECTED, cdc, documentNumber,
                    ProviderStatus.RECHAZADO.name(), error.code(), error.detail(), total, issue.updatedAt(), null);
        }
        String providerStatus = (String) result.get("providerStatus");
        return new InvoiceState(issue.idempotencyKey(), issue.id(), status(issue, providerStatus), cdc, documentNumber,
                providerStatus, (String) result.get("providerCode"), (String) result.get("providerMessage"), total,
                issue.updatedAt(), error);
    }

    private static InvoiceStatus status(Operation issue, String providerStatus) {
        return switch (issue.status()) {
            case SUCCEEDED -> ProviderStatus.parse(providerStatus).orElseThrow().invoiceStatus();
            case FAILED, DISCARDED -> InvoiceStatus.FAILED;
            case DEAD -> InvoiceStatus.DEAD;
            case PENDING, RUNNING, RETRY -> providerStatus == null ? InvoiceStatus.PENDING : InvoiceStatus.SUBMITTED;
        };
    }
}
