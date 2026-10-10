package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.identity.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AdminAuthIT extends AbstractPostgresIT {

    @Test
    void should_force_password_change_when_bootstrap_admin_logs_in() {
        MvcResult login = loginRaw(BOOTSTRAP_EMAIL, BOOTSTRAP_PASSWORD, "10.0.0.1", 200);
        JsonNode body = body(login);
        String token = body.get("accessToken").asString();
        assertThat(body.get("mustChangePassword").asBoolean()).isTrue();

        JsonNode blocked = body(perform(get("/api/admin/tenants").header("Authorization", bearer(token)), 403));
        assertThat(blocked.get("code").asString()).isEqualTo("password-change-required");
        JsonNode me = body(perform(get("/api/admin/auth/me").header("Authorization", bearer(token)), 200));
        assertThat(me.get("role").asString()).isEqualTo("platform_admin");

        perform(post("/api/admin/auth/password").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("currentPassword", BOOTSTRAP_PASSWORD, "newPassword", "a-brand-new-password"))), 204);
        JsonNode refreshed = body(perform(post("/api/admin/auth/refresh").cookie(refreshCookie(login)), 200));

        assertThat(refreshed.get("mustChangePassword").asBoolean()).isFalse();
        perform(get("/api/admin/tenants").header("Authorization", bearer(refreshed.get("accessToken").asString())), 200);
    }

    @Test
    void should_issue_secure_refresh_cookie_and_rotate_it_when_refreshing() {
        String email = createUser(Role.TENANT_ADMIN);
        MvcResult login = loginRaw(email, PASSWORD, "10.0.0.2", 200);
        Cookie original = refreshCookie(login);
        String setCookie = login.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/admin/auth");

        MvcResult refreshed = perform(post("/api/admin/auth/refresh").cookie(original), 200);

        assertThat(refreshCookie(refreshed).getValue()).isNotEqualTo(original.getValue());
        perform(post("/api/admin/auth/refresh").cookie(original), 401);
        perform(post("/api/admin/auth/refresh").cookie(refreshCookie(refreshed)), 200);
    }

    @Test
    void should_revoke_session_when_logging_out() {
        String email = createUser(Role.VIEWER);
        Cookie cookie = refreshCookie(loginRaw(email, PASSWORD, "10.0.0.3", 200));

        perform(post("/api/admin/auth/logout").cookie(cookie), 204);

        JsonNode problem = body(perform(post("/api/admin/auth/refresh").cookie(cookie), 401));
        assertThat(problem.get("code").asString()).isEqualTo("invalid-session");
    }

    @Test
    void should_lock_account_when_five_logins_fail() {
        String email = createUser(Role.OPERATOR);
        for (int i = 0; i < 5; i++) {
            JsonNode problem = body(loginRaw(email, "wrong-password-123", "10.0.1.1", 401));
            assertThat(problem.get("code").asString()).isEqualTo("invalid-credentials");
        }

        JsonNode locked = body(loginRaw(email, PASSWORD, "10.0.1.1", 423));

        assertThat(locked.get("code").asString()).isEqualTo("account-locked");
    }

    @Test
    void should_answer_401_problem_when_user_does_not_exist() {
        JsonNode problem = body(loginRaw("nobody@nexus.test", "whatever-password", "10.0.1.2", 401));

        assertThat(problem.get("type").asString()).isEqualTo("https://nexus.mindtechpy.net/problems/invalid-credentials");
        assertThat(problem.get("correlationId").asString()).isNotBlank();
    }

    @Test
    void should_rate_limit_when_ip_exceeds_ten_logins_per_minute() {
        String ip = "10.9." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
        for (int i = 0; i < 10; i++) {
            loginRaw("nobody-" + i + "@nexus.test", "whatever-password", ip, 401);
        }

        JsonNode problem = body(loginRaw("nobody@nexus.test", "whatever-password", ip, 429));

        assertThat(problem.get("code").asString()).isEqualTo("rate-limited");
    }

    @Test
    void should_reject_weak_password_when_creating_user() {
        String admin = platformAdminToken();
        UUID tenant = createTenant(admin);

        JsonNode problem = body(perform(post("/api/admin/users").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", "weak@nexus.test", "password", "short", "role", "viewer"))), 422));

        assertThat(problem.get("code").asString()).isEqualTo("weak-password");
    }

    private String createUser(Role role) {
        String admin = platformAdminToken();
        UUID tenant = createTenant(admin);
        String email = "user-" + UUID.randomUUID() + "@nexus.test";
        perform(post("/api/admin/users").param("tenantId", tenant.toString()).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", PASSWORD, "role", role.value()))), 201);
        return email;
    }

    private MvcResult loginRaw(String email, String password, String ip, int expected) {
        return perform(post("/api/admin/auth/login").with(remoteAddr(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))), expected);
    }

    private static Cookie refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("nexus_refresh");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
