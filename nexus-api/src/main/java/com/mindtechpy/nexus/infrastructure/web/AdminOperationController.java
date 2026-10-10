package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.usecase.OperationQueries;
import com.mindtechpy.nexus.application.usecase.ReplayOperation;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.operation.OperationStep;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operations and dead-letter queue of the console. Payloads are never returned. */
@Tag(name = "Admin · Operaciones")
@RestController
@RequestMapping("/api/admin/operations")
class AdminOperationController {

    record OperationView(UUID id, UUID tenantId, UUID integrationId, String flow, String kind, String idempotencyKey,
                         String status, String currentStep, int attempts, int maxAttempts, Instant nextRunAt,
                         String correlationId, Map<String, Object> result, ConnectorErrorView lastError,
                         String deadReason, Instant createdAt, Instant updatedAt, Instant finishedAt) {
        static OperationView of(Operation op) {
            return new OperationView(op.id(), op.tenantId().value(), op.integrationId(), op.flow().name(), op.kind().name(),
                    op.idempotencyKey(), op.status().name(), op.currentStep(), op.attempts(), op.maxAttempts(),
                    op.nextRunAt(), op.correlationId(), op.result(), ConnectorErrorView.of(op.lastError()),
                    op.deadReason(), op.createdAt(), op.updatedAt(), op.finishedAt());
        }
    }

    record StepView(UUID id, String step, int attempt, String status, String externalRef, Integer responseCode, String errorCode,
                    Instant startedAt, Instant finishedAt) {
        static StepView of(OperationStep step) {
            return new StepView(step.id(), step.step(), step.attempt(), step.status().name(), step.externalRef(), step.responseCode(),
                    step.errorCode(), step.startedAt(), step.finishedAt());
        }
    }

    /** The operation fields at top level plus its steps in execution order. */
    record OperationDetailView(@JsonUnwrapped OperationView operation, List<StepView> steps) {
    }

    private final OperationQueries operationQueries;
    private final ReplayOperation replayOperation;

    AdminOperationController(OperationQueries operationQueries, ReplayOperation replayOperation) {
        this.operationQueries = operationQueries;
        this.replayOperation = replayOperation;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Lista operaciones, más recientes primero; q busca por id, clave idempotente o correlationId")
    @GetMapping
    List<OperationView> list(@RequestParam(required = false) UUID tenantId, @RequestParam(required = false) Flow flow,
                             @RequestParam(required = false) OperationStatus status,
                             @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                             @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        var filter = new OperationRepository.Filter(flow, status, from, to, q);
        return operationQueries.list(TenantContext.currentUser(), TenantId.of(tenantId), filter, page, size)
                .stream().map(OperationView::of).toList();
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Contadores para el overview: por estado y flujo desde from (default 24 h) y tamaño actual de la DLQ")
    @GetMapping("/summary")
    OperationQueries.Summary summary(@RequestParam(required = false) UUID tenantId,
                                     @RequestParam(required = false) Instant from) {
        return operationQueries.summary(TenantContext.currentUser(), TenantId.of(tenantId), from);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Detalle con sus pasos (404 si es de otro tenant)")
    @GetMapping("/{id}")
    OperationDetailView get(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id) {
        OperationQueries.Detail detail = operationQueries.detail(TenantContext.currentUser(), TenantId.of(tenantId), id);
        return new OperationDetailView(OperationView.of(detail.operation()), detail.steps().stream().map(StepView::of).toList());
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Reprocesa una operación DEAD (misma clave idempotente; reanuda en el paso fallido); auditado")
    @PostMapping("/{id}/replay")
    OperationView replay(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id) {
        return OperationView.of(replayOperation.execute(TenantContext.currentUser(), TenantId.of(tenantId), id));
    }
}
