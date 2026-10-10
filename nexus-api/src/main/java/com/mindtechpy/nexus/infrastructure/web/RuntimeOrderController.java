package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.ReportOrderStatus;
import com.mindtechpy.nexus.application.usecase.StartOperation;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Channel orders for machine clients: the POS reports the final state of a channel sale (scope {@code orders:write}). */
@Tag(name = "Runtime · Pedidos")
@RestController
@RequestMapping("/api/v1/orders")
class RuntimeOrderController {

    /** As sent by the POS {@code ChannelOrderStatusProcessor}; {@code paidAmount} and {@code occurredAt} are informative. */
    record StatusRequest(@NotNull @Pattern(regexp = "COMPLETED|CANCELLED") String status, @Size(max = 60) String saleNumber,
                         Long paidAmount, @Size(max = 20) String paymentMethod, String occurredAt) {
    }

    record StartedView(UUID operationId, String status) {
        static StartedView of(StartOperation.Started started) {
            return new StartedView(started.operation().id(), started.operation().status().name());
        }
    }

    private final ReportOrderStatus reportOrderStatus;

    RuntimeOrderController(ReportOrderStatus reportOrderStatus) {
        this.reportOrderStatus = reportOrderStatus;
    }

    @Operation(summary = "El POS informa una venta de canal cobrada o anulada; 202 nueva, 200 repetida, 404 pedido desconocido")
    @PostMapping("/{channel}/{externalRef}/status")
    ResponseEntity<StartedView> reportStatus(@PathVariable String channel, @PathVariable String externalRef,
                                             @Valid @RequestBody StatusRequest request) {
        Actor client = TenantContext.currentClient(Scope.ORDERS_WRITE);
        StartOperation.Started started = reportOrderStatus.execute(client.tenantId(), new ReportOrderStatus.Report(channel,
                externalRef, request.status(), request.saleNumber(), request.paymentMethod()), MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(started.created() ? 202 : 200).body(StartedView.of(started));
    }
}
