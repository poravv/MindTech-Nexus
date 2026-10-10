package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.CreateUser;
import com.mindtechpy.nexus.application.usecase.ListUsers;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.TenantId;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** {@code tenantId} is only honoured for platform_admin; other roles always act on their own tenant. */
@Tag(name = "Admin · Usuarios")
@RestController
@RequestMapping("/api/admin/users")
class AdminUserController {

    record CreateUserRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 200) String password,
                             @NotBlank String role) {
        @Override
        public String toString() {
            return "CreateUserRequest[email=" + email + ", role=" + role + ", password=***]";
        }
    }

    record UserView(UUID id, UUID tenantId, String email, String role, String status, boolean mustChangePassword) {
        static UserView of(User user) {
            return new UserView(user.id(), user.tenantId() == null ? null : user.tenantId().value(), user.email(),
                    user.role().value(), user.status().name(), user.mustChangePassword());
        }
    }

    private final CreateUser createUser;
    private final ListUsers listUsers;

    AdminUserController(CreateUser createUser, ListUsers listUsers) {
        this.createUser = createUser;
        this.listUsers = listUsers;
    }

    @Operation(summary = "Lista usuarios del tenant (platform_admin: todos o ?tenantId)")
    @GetMapping
    List<UserView> list(@RequestParam(required = false) UUID tenantId) {
        return listUsers.execute(TenantContext.currentUser(), TenantId.of(tenantId)).stream().map(UserView::of).toList();
    }

    @Operation(summary = "Crea un usuario con contraseña inicial (debe cambiarla al primer login)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserView create(@RequestParam(required = false) UUID tenantId, @Valid @RequestBody CreateUserRequest request) {
        return UserView.of(createUser.execute(TenantContext.currentUser(), TenantId.of(tenantId), request.email(),
                request.password(), Role.fromValue(request.role())));
    }
}
