package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.ChangeIntegrationState;
import com.mindtechpy.nexus.application.usecase.IntegrationQueries;
import com.mindtechpy.nexus.application.usecase.RunCatalogSync;
import com.mindtechpy.nexus.application.usecase.StartOperation;
import com.mindtechpy.nexus.application.usecase.UpsertIntegration;
import com.mindtechpy.nexus.application.usecase.UpsertIntegration.Draft;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.integration.IntegrationPolicy;
import com.mindtechpy.nexus.domain.integration.IntegrationState;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Integrations of a tenant: create, reconfigure and switch state (with reason, audited). There is no
 * delete: {@code DISABLED} retires an integration while keeping its operation history.
 */
@Tag(name = "Admin · Integraciones")
@RestController
@RequestMapping("/api/admin/integrations")
class AdminIntegrationController {

    /** Omitted policy fields take the spec defaults. */
    record CreateIntegrationRequest(@NotNull Flow flow, @NotBlank @Size(max = 63) String code, UUID sourceConnectionId,
                                    @NotNull UUID targetConnectionId, IntegrationPolicy policy) {
    }

    /** Omitting {@code policy} keeps the current one. */
    record UpdateIntegrationRequest(@NotBlank @Size(max = 63) String code, UUID sourceConnectionId,
                                    @NotNull UUID targetConnectionId, IntegrationPolicy policy) {
    }

    record ChangeStateRequest(@NotNull IntegrationState state, @NotBlank @Size(max = 500) String reason) {
    }

    record IntegrationView(UUID id, UUID tenantId, String flow, String code, UUID sourceConnectionId,
                           UUID targetConnectionId, String state, IntegrationPolicy policy, int version, Instant updatedAt) {
        static IntegrationView of(Integration integration) {
            return new IntegrationView(integration.id(), integration.tenantId().value(), integration.flow().name(),
                    integration.code(), integration.sourceConnectionId(), integration.targetConnectionId(),
                    integration.state().name(), integration.policy(), integration.version(), integration.updatedAt());
        }
    }

    record RunView(UUID operationId, String status) {
    }

    private final UpsertIntegration upsertIntegration;
    private final IntegrationQueries integrationQueries;
    private final ChangeIntegrationState changeIntegrationState;
    private final RunCatalogSync runCatalogSync;

    AdminIntegrationController(UpsertIntegration upsertIntegration, IntegrationQueries integrationQueries,
                               ChangeIntegrationState changeIntegrationState, RunCatalogSync runCatalogSync) {
        this.upsertIntegration = upsertIntegration;
        this.integrationQueries = integrationQueries;
        this.changeIntegrationState = changeIntegrationState;
        this.runCatalogSync = runCatalogSync;
    }

    @Operation(summary = "Lista integraciones del tenant")
    @GetMapping
    List<IntegrationView> list(@RequestParam(required = false) UUID tenantId) {
        return integrationQueries.list(TenantContext.currentUser(), TenantId.of(tenantId)).stream().map(IntegrationView::of).toList();
    }

    @Operation(summary = "Salud por integración: SUSPENDED si está detenida; DEGRADED mientras tenga operaciones DEAD sin reprocesar ni "
            + "descartar (dead, la DLQ actual, sin importar la antigüedad); PROGRESSING si drena o hay operaciones en cola creadas desde "
            + "from (default 24 h); HEALTHY si todas las de la ventana terminaron (las FAILED de negocio se cuentan en byStatus pero no "
            + "degradan); NO_ACTIVITY sin operaciones en la ventana")
    @GetMapping("/health")
    List<IntegrationQueries.Health> health(@RequestParam(required = false) UUID tenantId, @RequestParam(required = false) Instant from) {
        return integrationQueries.health(TenantContext.currentUser(), TenantId.of(tenantId), from);
    }

    @Operation(summary = "Detalle de una integración (404 si es de otro tenant)")
    @GetMapping("/{id}")
    IntegrationView get(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id) {
        return IntegrationView.of(integrationQueries.get(TenantContext.currentUser(), TenantId.of(tenantId), id));
    }

    @Operation(summary = "Crea una integración (estado inicial ENABLED)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IntegrationView create(@RequestParam(required = false) UUID tenantId, @Valid @RequestBody CreateIntegrationRequest request) {
        Draft draft = new Draft(request.code(), request.sourceConnectionId(), request.targetConnectionId(), request.policy());
        return IntegrationView.of(upsertIntegration.create(TenantContext.currentUser(), TenantId.of(tenantId), request.flow(), draft));
    }

    @Operation(summary = "Reconfigura conexiones, código y política")
    @PutMapping("/{id}")
    IntegrationView update(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id,
                           @Valid @RequestBody UpdateIntegrationRequest request) {
        Draft draft = new Draft(request.code(), request.sourceConnectionId(), request.targetConnectionId(), request.policy());
        return IntegrationView.of(upsertIntegration.update(TenantContext.currentUser(), TenantId.of(tenantId), id, draft));
    }

    @Operation(summary = "Cambia el estado con motivo (EMERGENCY_STOP solo platform_admin); auditado")
    @PostMapping("/{id}/state")
    IntegrationView changeState(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id,
                                @Valid @RequestBody ChangeStateRequest request) {
        return IntegrationView.of(changeIntegrationState.execute(TenantContext.currentUser(), TenantId.of(tenantId), id,
                request.state(), request.reason()));
    }

    @Operation(summary = "CATALOG: encola una sincronización ahora (operator o superior); auditado")
    @PostMapping("/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RunView run(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id) {
        StartOperation.Started started = runCatalogSync.runNow(TenantContext.currentUser(), TenantId.of(tenantId), id);
        return new RunView(started.operation().id(), started.operation().status().name());
    }
}
