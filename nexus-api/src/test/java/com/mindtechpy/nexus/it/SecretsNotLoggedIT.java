package com.mindtechpy.nexus.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ExtendWith(OutputCaptureExtension.class)
class SecretsNotLoggedIT extends AbstractPostgresIT {

    @Test
    void should_not_log_secrets_when_running_auth_and_connection_flows(CapturedOutput output) {
        String admin = platformAdminToken();
        UUID tenant = createTenant(admin);
        String connectionSecret = "conn-secret-" + UUID.randomUUID();
        JsonNode issued = body(perform(post("/api/admin/api-clients").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "POS", "scopes", List.of("fiscal:issue")))), 201));
        String clientSecret = issued.get("clientSecret").asString();
        String basic = Base64.getEncoder().encodeToString((issued.get("client").get("clientId").asString() + ":" + clientSecret)
                .getBytes(StandardCharsets.UTF_8));
        String accessToken = body(perform(post("/oauth/token").header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials"), 200))
                .get("access_token").asString();
        perform(post("/api/admin/connections").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("connector", "GIFTHUB", "name", "gh", "baseUrl", "http://api.comercio-central.svc.cluster.local:3000",
                        "credentials", Map.of("apiKey", connectionSecret)))), 201);
        perform(post("/api/admin/auth/login").with(remoteAddr("10.20.0.1")).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", "ghost@nexus.test", "password", "ghost-password-value"))), 401);

        assertThat(output.getAll())
                .contains("[NEXUS-AUTH] token m2m emitido")
                .doesNotContain(clientSecret)
                .doesNotContain(accessToken)
                .doesNotContain(connectionSecret)
                .doesNotContain("ghost-password-value")
                .doesNotContain(PASSWORD);
    }

    @Test
    void should_mask_sensitive_values_when_code_logs_them_by_mistake(CapturedOutput output) {
        LoggerFactory.getLogger("masking-check").info("Authorization: Bearer abc.def.ghi X-Secret-Key: sk-leak password=hunter2-leak");

        assertThat(output.getAll())
                .contains("Authorization: ***")
                .doesNotContain("abc.def.ghi")
                .doesNotContain("sk-leak")
                .doesNotContain("hunter2-leak");
    }
}
