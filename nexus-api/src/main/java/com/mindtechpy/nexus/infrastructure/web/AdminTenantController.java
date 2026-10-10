package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.CreateTenant;
import com.mindtechpy.nexus.application.usecase.ListTenants;
import com.mindtechpy.nexus.domain.tenant.Tenant;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Tag(name = "Admin · Tenants")
@RestController
@RequestMapping("/api/admin/tenants")
class AdminTenantController {

    record CreateTenantRequest(@NotBlank @Size(max = 63) String slug, @NotBlank @Size(max = 200) String name) {
    }

    record TenantView(UUID id, String slug, String name, String status, Instant createdAt) {
        static TenantView of(Tenant tenant) {
            return new TenantView(tenant.id().value(), tenant.slug(), tenant.name(), tenant.status().name(), tenant.createdAt());
        }
    }

    private final CreateTenant createTenant;
    private final ListTenants listTenants;

    AdminTenantController(CreateTenant createTenant, ListTenants listTenants) {
        this.createTenant = createTenant;
        this.listTenants = listTenants;
    }

    @Operation(summary = "Lista tenants (platform_admin)")
    @GetMapping
    List<TenantView> list() {
        return listTenants.execute(TenantContext.currentUser()).stream().map(TenantView::of).toList();
    }

    @Operation(summary = "Crea un tenant (platform_admin)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TenantView create(@Valid @RequestBody CreateTenantRequest request) {
        return TenantView.of(createTenant.execute(TenantContext.currentUser(), request.slug(), request.name()));
    }
}
