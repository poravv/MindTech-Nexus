package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.ConnectionQueries;
import com.mindtechpy.nexus.application.usecase.TestConnection;
import com.mindtechpy.nexus.application.usecase.UpsertConnection;
import com.mindtechpy.nexus.application.usecase.UpsertConnection.Draft;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import java.util.Map;
import java.util.UUID;

/** Connections to external systems. Credentials are write-only and never appear in any response. */
@Tag(name = "Admin · Conexiones")
@RestController
@RequestMapping("/api/admin/connections")
class AdminConnectionController {

    record CreateConnectionRequest(@NotNull ConnectorType connector, @NotBlank @Size(max = 100) String name,
                                   @NotBlank @Size(max = 500) String baseUrl, @NotEmpty Map<String, String> credentials,
                                   Map<String, Object> settings) {
        @Override
        public String toString() {
            return "CreateConnectionRequest[connector=" + connector + ", name=" + name + ", credentials=***]";
        }
    }

    /** Omitting {@code credentials} keeps the stored ones. */
    record UpdateConnectionRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 500) String baseUrl,
                                   Map<String, String> credentials, Map<String, Object> settings) {
        @Override
        public String toString() {
            return "UpdateConnectionRequest[name=" + name + ", credentials=***]";
        }
    }

    record ConnectionView(UUID id, UUID tenantId, String connector, String name, String baseUrl,
                          Map<String, Object> settings, boolean hasCredentials, Instant lastTestAt, Boolean lastTestOk) {
        static ConnectionView of(Connection connection) {
            return new ConnectionView(connection.id(), connection.tenantId().value(), connection.connector().name(),
                    connection.name(), connection.baseUrl(), connection.settings(),
                    connection.sealedCredentials() != null, connection.lastTestAt(), connection.lastTestOk());
        }
    }

    private final UpsertConnection upsertConnection;
    private final ConnectionQueries connectionQueries;
    private final TestConnection testConnection;

    AdminConnectionController(UpsertConnection upsertConnection, ConnectionQueries connectionQueries, TestConnection testConnection) {
        this.upsertConnection = upsertConnection;
        this.connectionQueries = connectionQueries;
        this.testConnection = testConnection;
    }

    @Operation(summary = "Lista conexiones del tenant")
    @GetMapping
    List<ConnectionView> list(@RequestParam(required = false) UUID tenantId) {
        return connectionQueries.list(TenantContext.currentUser(), TenantId.of(tenantId)).stream().map(ConnectionView::of).toList();
    }

    @Operation(summary = "Detalle de una conexión (404 si es de otro tenant)")
    @GetMapping("/{id}")
    ConnectionView get(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id) {
        return ConnectionView.of(connectionQueries.get(TenantContext.currentUser(), TenantId.of(tenantId), id));
    }

    @Operation(summary = "Crea una conexión; base_url validada contra la allowlist y credenciales cifradas")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConnectionView create(@RequestParam(required = false) UUID tenantId, @Valid @RequestBody CreateConnectionRequest request) {
        Draft draft = new Draft(request.name(), request.baseUrl(), request.credentials(), request.settings());
        return ConnectionView.of(upsertConnection.create(TenantContext.currentUser(), TenantId.of(tenantId), request.connector(), draft));
    }

    @Operation(summary = "Edita una conexión; sin credentials conserva las actuales")
    @PutMapping("/{id}")
    ConnectionView update(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id,
                          @Valid @RequestBody UpdateConnectionRequest request) {
        Draft draft = new Draft(request.name(), request.baseUrl(), request.credentials(), request.settings());
        return ConnectionView.of(upsertConnection.update(TenantContext.currentUser(), TenantId.of(tenantId), id, draft));
    }

    @Operation(summary = "Prueba la conexión; informa solo éxito/fallo")
    @PostMapping("/{id}/test")
    TestConnection.Result test(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id) {
        return testConnection.execute(TenantContext.currentUser(), TenantId.of(tenantId), id);
    }
}
