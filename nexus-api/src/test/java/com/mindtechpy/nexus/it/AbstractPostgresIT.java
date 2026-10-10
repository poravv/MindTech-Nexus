package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.port.out.PasswordHasherPort;
import com.mindtechpy.nexus.application.port.out.UserRepository;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.identity.User;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full application against a real Postgres 16 (one container and one Spring context for all
 * ITs). Keys are generated per run; nothing comes from {@code .env}. Each test creates its own tenants
 * and users, so tests are independent without cleaning the database. The background worker is off: tests
 * drive the queue explicitly to stay deterministic.
 */
@SpringBootTest(properties = {"nexus.worker.enabled=false", "nexus.queue.max-unfinished-per-tenant=150"})
@AutoConfigureMockMvc
@Import(TestSagaConfig.class)
public abstract class AbstractPostgresIT {

    protected static final String BOOTSTRAP_EMAIL = "bootstrap@nexus.test";
    protected static final String BOOTSTRAP_PASSWORD = "bootstrap-password-123";
    protected static final String PASSWORD = "correct-horse-battery";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.10-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("nexus.security.jwt-secret", () -> randomBase64(64));
        registry.add("nexus.security.master-key", () -> randomBase64(32));
        registry.add("nexus.bootstrap.admin-email", () -> BOOTSTRAP_EMAIL);
        registry.add("nexus.bootstrap.admin-password", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordHasherPort hasher;

    /** Inserts an active user that already changed its password and returns its access token. */
    protected String tokenFor(Role role, UUID tenantId) {
        String email = role.value() + "-" + UUID.randomUUID() + "@nexus.test";
        User created = User.create(TenantId.of(tenantId), email, hasher.hash(PASSWORD), role);
        users.insert(created.withNewPassword(created.passwordHash()));
        return login(email, PASSWORD).get("accessToken").asString();
    }

    protected String platformAdminToken() {
        return tokenFor(Role.PLATFORM_ADMIN, null);
    }

    protected JsonNode login(String email, String password) {
        return body(perform(post("/api/admin/auth/login").with(remoteAddr(UUID.randomUUID().toString()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))), 200));
    }

    protected UUID createTenant(String adminToken) {
        String slug = "t-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode tenant = body(perform(post("/api/admin/tenants").header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("slug", slug, "name", "Tenant " + slug))), 201));
        return UUID.fromString(tenant.get("id").asString());
    }

    protected MvcResult perform(MockHttpServletRequestBuilder request, int expectedStatus) {
        MvcResult result;
        try {
            result = mvc.perform(request).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(result.getResponse().getStatus())
                .as(() -> "respuesta: " + contentOf(result))
                .isEqualTo(expectedStatus);
        return result;
    }

    private static String contentOf(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<sin cuerpo>";
        }
    }

    protected JsonNode body(MvcResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String randomBase64(int bytes) {
        byte[] buffer = new byte[bytes];
        new SecureRandom().nextBytes(buffer);
        return Base64.getEncoder().encodeToString(buffer);
    }
}
