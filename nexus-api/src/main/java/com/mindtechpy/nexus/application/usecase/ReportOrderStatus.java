package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.util.HashMap;
import java.util.Map;

/**
 * The POS reports the final state of a channel sale ({@code POST /api/v1/orders/{channel}/{externalRef}/status},
 * sent by its {@code ChannelOrderStatusProcessor} when the sale is collected or voided). Starts
 * {@code ORDER_STATUS} with key {@code {externalRef}:{status}} on the integration that created the order, so
 * the POS may repeat the call safely. {@code occurredAt} and {@code paidAmount} are not part of the payload:
 * GiftHub marks the order total as paid, and keeping them out keeps the request hash stable across retries.
 */
public class ReportOrderStatus {

    public record Report(String channel, String externalRef, String status, String saleNumber, String paymentMethod) {
    }

    private final OperationRepository operations;
    private final StartOperation startOperation;

    public ReportOrderStatus(OperationRepository operations, StartOperation startOperation) {
        this.operations = operations;
        this.startOperation = startOperation;
    }

    /** @throws DomainException 404 when Nexus did not create that order for the tenant */
    public StartOperation.Started execute(TenantId tenantId, Report report, String correlationId) {
        if (!"COMPLETED".equals(report.status()) && !"CANCELLED".equals(report.status())) {
            throw DomainException.invalid("invalid-status", "status debe ser COMPLETED o CANCELLED");
        }
        Operation created = operations.findByKey(tenantId, OperationKind.CREATE_ORDER, report.externalRef())
                .filter(op -> "GIFTHUB".equals(report.channel()))
                .orElseThrow(() -> DomainException.notFound("Pedido"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("target", report.status());
        payload.put("orderId", report.externalRef());
        payload.put("saleNumber", report.saleNumber());
        payload.put("paymentMethod", report.paymentMethod());
        return startOperation.execute(new StartOperation.Command(tenantId, created.integrationId(), OperationKind.ORDER_STATUS,
                report.externalRef() + ":" + report.status(), payload, correlationId));
    }
}
