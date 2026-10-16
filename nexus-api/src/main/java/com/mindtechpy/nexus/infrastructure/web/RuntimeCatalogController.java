package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.RunCatalogSync;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Catalog for machine clients: the POS asks for an immediate sync, e.g. after a product sells out (scope {@code catalog:sync}). */
@Tag(name = "Runtime · Catálogo")
@RestController
@RequestMapping("/api/v1/catalog")
class RuntimeCatalogController {

    private final RunCatalogSync runCatalogSync;

    RuntimeCatalogController(RunCatalogSync runCatalogSync) {
        this.runCatalogSync = runCatalogSync;
    }

    @Operation(summary = "El POS pide sincronizar el catálogo ya; 202 si se creó alguna corrida, 200 si ya existía la de este minuto, "
            + "404 sin integración de catálogo habilitada, 409 con una corrida en curso")
    @PostMapping("/sync")
    ResponseEntity<List<RunCatalogSync.Requested>> requestSync() {
        Actor client = TenantContext.currentClient(Scope.CATALOG_SYNC);
        List<RunCatalogSync.Requested> requested = runCatalogSync.requestFromPos(client.tenantId());
        boolean anyCreated = requested.stream().anyMatch(RunCatalogSync.Requested::created);
        return ResponseEntity.status(anyCreated ? 202 : 200).body(requested);
    }
}
