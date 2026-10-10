package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.OperationQueries;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Operation status for machine clients (scope {@code operations:read}); the tenant comes from the token. */
@Tag(name = "Runtime · Operaciones")
@RestController
@RequestMapping("/api/v1/operations")
class RuntimeOperationController {

    /** {@code message} is the field the POS shows when a cancellation ends FAILED or DEAD. */
    record LastErrorView(String kind, String code, String message, Integer httpStatus) {
        static LastErrorView of(ConnectorError error) {
            return error == null ? null : new LastErrorView(error.kind().name(), error.code(), error.detail(), error.httpStatus());
        }
    }

    record OperationStatusView(UUID id, String flow, String kind, String idempotencyKey, String status, String currentStep,
                               int attempts, Map<String, Object> result, LastErrorView lastError, String deadReason,
                               Instant createdAt, Instant updatedAt, Instant finishedAt) {
        static OperationStatusView of(Operation op) {
            return new OperationStatusView(op.id(), op.flow().name(), op.kind().name(), op.idempotencyKey(),
                    op.status().name(), op.currentStep(), op.attempts(), op.result(), LastErrorView.of(op.lastError()),
                    op.deadReason(), op.createdAt(), op.updatedAt(), op.finishedAt());
        }
    }

    private final OperationQueries operationQueries;

    RuntimeOperationController(OperationQueries operationQueries) {
        this.operationQueries = operationQueries;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Estado de una operación del tenant del token (404 si es de otro tenant)")
    @GetMapping("/{id}")
    OperationStatusView get(@PathVariable UUID id) {
        Actor client = TenantContext.currentClient(Scope.OPERATIONS_READ);
        return OperationStatusView.of(operationQueries.forClient(client.tenantId(), id));
    }
}
