package com.mindtechpy.nexus.it;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class OAuthTokenIT extends AbstractPostgresIT {

    private String adminToken;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        adminToken = platformAdminToken();
        tenantId = createTenant(adminToken);
    }

    @Test
    void should_issue_jwt_with_tenant_and_scopes_when_client_is_valid() {
        JsonNode client = createClient(List.of("fiscal:issue", "fiscal:read"));

        JsonNode token = body(requestToken(clientId(client), secret(client), 200));

        assertThat(token.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(token.get("expires_in").asLong()).isEqualTo(900);
        JsonNode claims = claims(token.get("access_token").asString());
        assertThat(claims.get("tenant_id").asString()).isEqualTo(tenantId.toString());
        assertThat(claims.get("sub").asString()).isEqualTo(clientId(client));
        assertThat(claims.get("typ").asString()).isEqualTo("client");
        assertThat(claims.get("scope").asString()).isEqualTo("fiscal:issue fiscal:read");
    }

    @Test
    void should_return_401_when_secret_is_wrong() {
        JsonNode client = createClient(List.of("fiscal:read"));

        JsonNode problem = body(requestToken(clientId(client), "wrong-secret", 401));

        assertThat(problem.get("code").asString()).isEqualTo("invalid-client");
    }

    @Test
    void should_accept_previous_secret_for_24h_when_secret_is_rotated() {
        JsonNode client = createClient(List.of("fiscal:read"));
        String oldSecret = secret(client);
        String id = client.get("client").get("id").asString();

        JsonNode rotated = body(perform(post("/api/admin/api-clients/{id}/rotate", id).param("tenantId", tenantId.toString())
                .header("Authorization", bearer(adminToken)), 200));

        requestToken(clientId(client), secret(rotated), 200);
        requestToken(clientId(client), oldSecret, 200);

        jdbc.sql("UPDATE api_clients SET secret_rotated_at = now() - interval '25 hours' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
        requestToken(clientId(client), oldSecret, 401);
        requestToken(clientId(client), secret(rotated), 200);
    }

    @Test
    void should_return_401_when_client_is_revoked() {
        JsonNode client = createClient(List.of("fiscal:read"));
        perform(post("/api/admin/api-clients/{id}/revoke", client.get("client").get("id").asString())
                .param("tenantId", tenantId.toString()).header("Authorization", bearer(adminToken)), 200);

        requestToken(clientId(client), secret(client), 401);
    }

    @Test
    void should_reject_when_grant_type_is_not_client_credentials() {
        JsonNode client = createClient(List.of("fiscal:read"));

        perform(post("/oauth/token").header("Authorization", basic(clientId(client), secret(client)))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "password"), 422);
    }

    @Test
    void should_keep_token_types_apart_when_calling_runtime_and_admin_apis() {
        JsonNode client = createClient(List.of("fiscal:read"));
        String clientToken = body(requestToken(clientId(client), secret(client), 200)).get("access_token").asString();

        perform(get("/api/admin/tenants").header("Authorization", bearer(clientToken)), 403);
        perform(get("/api/v1/operations/x").header("Authorization", bearer(adminToken)), 403);
        perform(get("/api/v1/operations/x"), 401);
        perform(get("/api/v1/operations/x").header("Authorization", bearer("not-a-jwt")), 401);
    }

    @Test
    void should_reject_client_creation_when_scope_is_unknown() {
        JsonNode problem = body(perform(post("/api/admin/api-clients").param("tenantId", tenantId.toString())
                .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "POS", "scopes", List.of("admin:all")))), 422));

        assertThat(problem.get("code").asString()).isEqualTo("invalid-scope");
    }

    private JsonNode createClient(List<String> scopes) {
        return body(perform(post("/api/admin/api-clients").param("tenantId", tenantId.toString())
                .header("Authorization", bearer(adminToken)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "POS piloto", "scopes", scopes))), 201));
    }

    private org.springframework.test.web.servlet.MvcResult requestToken(String clientId, String secret, int expected) {
        return perform(post("/oauth/token").header("Authorization", basic(clientId, secret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials"), expected);
    }

    private static String clientId(JsonNode issued) {
        return issued.get("client").get("clientId").asString();
    }

    private static String secret(JsonNode issued) {
        return issued.get("clientSecret").asString();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode claims(String jwt) {
        return json.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
    }
}
