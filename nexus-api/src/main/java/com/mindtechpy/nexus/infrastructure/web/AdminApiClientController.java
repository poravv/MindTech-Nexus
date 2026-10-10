package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.CreateApiClient;
import com.mindtechpy.nexus.application.usecase.IssuedSecret;
import com.mindtechpy.nexus.application.usecase.ListApiClients;
import com.mindtechpy.nexus.application.usecase.RevokeApiClient;
import com.mindtechpy.nexus.application.usecase.RotateApiClientSecret;
import com.mindtechpy.nexus.domain.identity.ApiClient;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Machine clients of a tenant. The secret is shown only in the create and rotate responses. */
@Tag(name = "Admin · Clientes m2m")
@RestController
@RequestMapping("/api/admin/api-clients")
class AdminApiClientController {

    record CreateApiClientRequest(@NotBlank @Size(max = 200) String name, @NotEmpty Set<String> scopes) {
    }

    record ApiClientView(UUID id, UUID tenantId, String clientId, String name, List<String> scopes, String status,
                         Instant secretRotatedAt, Instant lastUsedAt) {
        static ApiClientView of(ApiClient client) {
            return new ApiClientView(client.id(), client.tenantId().value(), client.clientId(), client.name(),
                    client.scopes().stream().map(Scope::value).sorted().toList(), client.status().name(),
                    client.secretRotatedAt(), client.lastUsedAt());
        }
    }

    record IssuedSecretView(ApiClientView client, String clientSecret) {
        static IssuedSecretView of(IssuedSecret issued) {
            return new IssuedSecretView(ApiClientView.of(issued.client()), issued.secret());
        }
    }

    private final CreateApiClient createApiClient;
    private final RotateApiClientSecret rotateApiClientSecret;
    private final RevokeApiClient revokeApiClient;
    private final ListApiClients listApiClients;

    AdminApiClientController(CreateApiClient createApiClient, RotateApiClientSecret rotateApiClientSecret,
                             RevokeApiClient revokeApiClient, ListApiClients listApiClients) {
        this.createApiClient = createApiClient;
        this.rotateApiClientSecret = rotateApiClientSecret;
        this.revokeApiClient = revokeApiClient;
        this.listApiClients = listApiClients;
    }

    @Operation(summary = "Lista clientes m2m del tenant (sin secretos)")
    @GetMapping
    List<ApiClientView> list(@RequestParam(required = false) UUID tenantId) {
        return listApiClients.execute(TenantContext.currentUser(), TenantId.of(tenantId)).stream().map(ApiClientView::of).toList();
    }

    @Operation(summary = "Crea un cliente m2m; el secreto se devuelve una única vez")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IssuedSecretView create(@RequestParam(required = false) UUID tenantId, @Valid @RequestBody CreateApiClientRequest request) {
        return IssuedSecretView.of(createApiClient.execute(TenantContext.currentUser(), TenantId.of(tenantId),
                request.name(), request.scopes()));
    }

    @Operation(summary = "Rota el secreto; el anterior sigue válido 24 h")
    @PostMapping("/{id}/rotate")
    IssuedSecretView rotate(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id) {
        return IssuedSecretView.of(rotateApiClientSecret.execute(TenantContext.currentUser(), TenantId.of(tenantId), id));
    }

    @Operation(summary = "Revoca el cliente: no puede obtener nuevos tokens")
    @PostMapping("/{id}/revoke")
    ApiClientView revoke(@RequestParam(required = false) UUID tenantId, @PathVariable UUID id) {
        return ApiClientView.of(revokeApiClient.execute(TenantContext.currentUser(), TenantId.of(tenantId), id));
    }
}
