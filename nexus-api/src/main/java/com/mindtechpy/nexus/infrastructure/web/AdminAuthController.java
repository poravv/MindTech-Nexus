package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.usecase.ChangePassword;
import com.mindtechpy.nexus.application.usecase.GetCurrentUser;
import com.mindtechpy.nexus.application.usecase.LoginUser;
import com.mindtechpy.nexus.application.usecase.Logout;
import com.mindtechpy.nexus.application.usecase.RefreshSession;
import com.mindtechpy.nexus.application.usecase.SessionGrant;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.identity.UserSession;
import com.mindtechpy.nexus.infrastructure.security.HmacJwtSupport;
import com.mindtechpy.nexus.infrastructure.security.HmacJwtSupport.IssuedToken;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * Console authentication: short access JWT in the body, opaque refresh token in an
 * {@code HttpOnly; Secure; SameSite=Strict} cookie scoped to {@code /api/admin/auth} (spec §5.2).
 */
@Tag(name = "Admin · Auth")
@RestController
@RequestMapping("/api/admin/auth")
class AdminAuthController {

    static final String REFRESH_COOKIE = "nexus_refresh";
    private static final String COOKIE_PATH = "/api/admin/auth";

    record LoginRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";
        }
    }

    record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 200) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    record AccessTokenResponse(String accessToken, String tokenType, long expiresIn, boolean mustChangePassword) {
    }

    record MeResponse(UUID id, String email, String role, UUID tenantId, boolean mustChangePassword) {
    }

    private final LoginUser loginUser;
    private final RefreshSession refreshSession;
    private final Logout logout;
    private final ChangePassword changePassword;
    private final GetCurrentUser getCurrentUser;
    private final HmacJwtSupport jwt;
    private final ClockPort clock;

    AdminAuthController(LoginUser loginUser, RefreshSession refreshSession, Logout logout, ChangePassword changePassword,
                        GetCurrentUser getCurrentUser, HmacJwtSupport jwt, ClockPort clock) {
        this.loginUser = loginUser;
        this.refreshSession = refreshSession;
        this.logout = logout;
        this.changePassword = changePassword;
        this.getCurrentUser = getCurrentUser;
        this.jwt = jwt;
        this.clock = clock;
    }

    @Operation(summary = "Login con email y contraseña; devuelve access token y setea la cookie de refresh")
    @PostMapping("/login")
    ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        SessionGrant grant = loginUser.execute(request.email(), request.password(), http.getRemoteAddr(),
                http.getHeader(HttpHeaders.USER_AGENT));
        return withSession(grant);
    }

    @Operation(summary = "Renueva el access token usando la cookie de refresh (rota la cookie)")
    @PostMapping("/refresh")
    ResponseEntity<AccessTokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return withSession(refreshSession.execute(refreshToken));
    }

    @Operation(summary = "Revoca la sesión de refresh y borra la cookie")
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        logout.execute(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString()).build();
    }

    @Operation(summary = "Usuario autenticado")
    @GetMapping("/me")
    MeResponse me() {
        User user = getCurrentUser.execute(TenantContext.currentUserAllowingPasswordChange());
        return new MeResponse(user.id(), user.email(), user.role().value(),
                user.tenantId() == null ? null : user.tenantId().value(), user.mustChangePassword());
    }

    @Operation(summary = "Cambia la contraseña propia (obligatorio tras el bootstrap); luego usar /refresh")
    @PostMapping("/password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        changePassword.execute(TenantContext.currentUserAllowingPasswordChange(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<AccessTokenResponse> withSession(SessionGrant grant) {
        IssuedToken token = jwt.issueForUser(grant.user(), clock.now());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(grant.refreshToken(), UserSession.LIFETIME).toString())
                .body(new AccessTokenResponse(token.value(), "Bearer", token.expiresInSeconds(), grant.user().mustChangePassword()));
    }

    private static ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
