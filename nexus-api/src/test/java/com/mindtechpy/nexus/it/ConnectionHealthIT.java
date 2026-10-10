package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.infrastructure.connectors.sifen.SifenHmacSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code POST /api/admin/connections/{id}/test} against WireMock: one authenticated read-only GET per connector.
 * Own context so {@code localhost} (the fakes) is inside the allowlist.
 */
@SpringBootTest(properties = {"nexus.worker.enabled=false", "nexus.queue.max-unfinished-per-tenant=150",
        "nexus.connectors.allowed-hosts=*.svc.cluster.local,localhost"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConnectionHealthIT extends AbstractConnectorIT {

    static final WireMockServer SIFEN = new WireMockServer(options().dynamicPort());
    private static final String SIFEN_SECRET = "sk-health-" + UUID.randomUUID();

    static {
        SIFEN.start();
    }

    @Test
    void should_report_ok_when_pos_answers_the_catalog_read_with_the_api_key() {
        stubPos(get(urlPathEqualTo(pos("/api/integration/catalog"))).withQueryParam("page", equalTo("1"))
                .withQueryParam("limit", equalTo("1")).withHeader("X-Api-Key", equalTo("pv_live_test"))
                .willReturn(okJson("{\"items\":[],\"page\":1,\"limit\":1,\"total\":0}")));

        MvcResult tested = test(posConnection);

        assertThat(body(tested).get("ok").asBoolean()).isTrue();
        assertThat(content(tested)).doesNotContain("pv_live_test");
        assertThat(POS.findAll(anyRequestedFor(urlPathMatching("/" + tenant + "/.*")))).hasSize(1)
                .allSatisfy(request -> assertThat(request.getMethod().getName()).isEqualTo("GET"));
    }

    @Test
    void should_report_rejected_credentials_without_echoing_them_when_gifthub_answers_401() {
        stubGiftHub(get(urlPathEqualTo(gh("/products"))).withQueryParam("limit", equalTo("1"))
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"API key inválida: gh_live_test\",\"code\":\"UNAUTHORIZED\"}")));

        MvcResult tested = test(giftHubConnection);

        JsonNode result = body(tested);
        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("detail").asString()).contains("401").contains("credenciales rechazadas");
        assertThat(content(tested)).doesNotContain("gh_live_test");
        assertThat(giftHubRequests("GET", "/products").getFirst().getHeader("Authorization")).isEqualTo("Bearer gh_live_test");
        assertThat(lastTestOk(giftHubConnection)).isFalse();
    }

    @Test
    void should_sign_the_sifen_read_like_every_sifen_request() {
        UUID sifen = insertConnection(ConnectorType.SIFEN, SIFEN, Map.of("accessKey", "ak-health", "secretKey", SIFEN_SECRET), Map.of());
        SIFEN.stubFor(get(urlPathEqualTo("/" + tenant + "/api/v1/facturas")).withHeader("X-Access-Key", equalTo("ak-health"))
                .willReturn(okJson("{\"items\":[],\"page\":0,\"size\":1,\"totalItems\":0,\"totalPages\":0}")));

        MvcResult tested = test(sifen);

        assertThat(body(tested).get("ok").asBoolean()).isTrue();
        assertThat(content(tested)).doesNotContain(SIFEN_SECRET);
        List<LoggedRequest> requests = SIFEN.findAll(getRequestedFor(urlPathEqualTo("/" + tenant + "/api/v1/facturas")));
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.getFirst();
        assertThat(request.getUrl()).isEqualTo("/" + tenant + "/api/v1/facturas?page=0&size=1");
        assertThat(request.getHeader("X-Signature")).isEqualTo(SifenHmacSigner.sign(SIFEN_SECRET, "GET", request.getUrl(),
                request.getHeader("X-Timestamp"), request.getHeader("X-Nonce"), new byte[0]));
    }

    @Test
    void should_not_call_a_base_url_that_left_the_allowlist() {
        UUID outside = insertConnection(ConnectorType.POS, POS, Map.of("apiKey", "pv_live_test"), Map.of());
        jdbc.sql("UPDATE connections SET base_url = 'http://pos.example.com' WHERE id = :id").param("id", outside).update();

        JsonNode result = body(test(outside));

        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("detail").asString()).contains("allowlist");
    }

    @Test
    void should_report_failure_when_the_remote_system_is_unreachable() {
        UUID dead = insertConnection(ConnectorType.GIFTHUB, GIFTHUB, Map.of("apiKey", "gh_live_test"), Map.of());
        jdbc.sql("UPDATE connections SET base_url = 'http://localhost:1' WHERE id = :id").param("id", dead).update();

        JsonNode result = body(test(dead));

        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("detail").asString()).contains("connect-failed");
    }

    private MvcResult test(UUID connection) {
        return perform(post("/api/admin/connections/{id}/test", connection).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 200);
    }

    private boolean lastTestOk(UUID connection) {
        return jdbc.sql("SELECT last_test_ok FROM connections WHERE id = :id").param("id", connection).query(Boolean.class).single();
    }
}
