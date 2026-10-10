package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mindtechpy.nexus.application.usecase.RunCatalogSync;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code [NEXUS-CATALOG]} POS catalog pages → GiftHub bulk upserts, run from the console or the scheduler. */
class CatalogSyncIT extends AbstractConnectorIT {

    private static final int TOTAL = 1001;

    @Autowired
    private RunCatalogSync runCatalogSync;

    @Test
    void should_push_every_pos_page_as_one_gifthub_bulk_and_record_product_mappings() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        stubCatalogPages();
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).willReturn(okJson(bulkResponse()).withTransformers("response-template")));

        Operation op = runNow(integration);
        drain();

        Operation done = reload(op.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("pages", 3).containsEntry("fetched", 1001).containsEntry("total", 1001);
        var bulks = giftHubRequests("PUT", "/products/bulk");
        assertThat(bulks).hasSize(3);
        assertThat(bulks).extracting(r -> jsonOf(r).get("products").size()).containsExactly(500, 500, 1);
        JsonNode first = jsonOf(bulks.getFirst()).get("products").get(0);
        assertThat(first.get("external_id").asString()).isEqualTo("P-0001");
        assertThat(first.get("price_pyg").asLong()).isEqualTo(1100);
        assertThat(first.get("stock_qty").asLong()).isEqualTo(3);
        assertThat(first.get("available").asBoolean()).isTrue();
        assertThat(first.get("category").asString()).isEqualTo("Panificados");
        assertThat(first.has("image_url")).isFalse();
        assertThat(jsonOf(bulks.get(2)).get("products").get(0).get("available").asBoolean()).isFalse();
        assertThat(posRequests("GET", "/api/integration/catalog")).extracting(r -> r.queryParameter("page").firstValue())
                .containsExactly("1", "2", "3");
        assertThat(jdbc.sql("SELECT count(*) FROM entity_mappings WHERE tenant_id = :t AND entity_type = 'PRODUCT'")
                .param("t", tenant).query(Long.class).single()).isEqualTo(1001);
    }

    @Test
    void should_resume_at_the_failed_page_when_a_gifthub_bulk_fails() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        stubCatalogPages();
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).inScenario("bulk").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(bulkResponse()).withTransformers("response-template")).willSetStateTo("fail-once"));
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).inScenario("bulk").whenScenarioStateIs("fail-once")
                .willReturn(serverError()).willSetStateTo("ok"));
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).inScenario("bulk").whenScenarioStateIs("ok")
                .willReturn(okJson(bulkResponse()).withTransformers("response-template")));

        Operation op = runNow(integration);
        drain();
        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.RETRY);
        makeDue(op.id());
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(posRequests("GET", "/api/integration/catalog")).extracting(r -> r.queryParameter("page").firstValue())
                .containsExactly("1", "2", "2", "3");
    }

    @Test
    void should_only_preview_when_gifthub_connection_is_in_dry_run() {
        UUID dryRunGiftHub = insertConnection(ConnectorType.GIFTHUB, GIFTHUB, Map.of("apiKey", "gh_live_test"), Map.of("dryRun", true));
        UUID integration = createIntegration("CATALOG", posConnection, dryRunGiftHub);
        stubPos(get(urlPathEqualTo(pos("/api/integration/catalog"))).willReturn(okJson(catalogPage(1, 1, 1))));
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).withQueryParam("dry_run", equalTo("1")).willReturn(okJson(
                "{\"dry_run\":true,\"rows\":[{\"index\":0,\"action\":\"crear\",\"externalId\":\"P-0001\",\"error\":null}]}")));

        Operation op = runNow(integration);
        drain();

        Operation done = reload(op.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("created", 1).containsEntry("dryRun", true);
        assertThat(jdbc.sql("SELECT count(*) FROM entity_mappings WHERE tenant_id = :t").param("t", tenant).query(Long.class).single()).isZero();
    }

    @Test
    void should_create_one_run_per_window_when_scheduler_ticks_repeatedly() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);

        runCatalogSync.scheduleDue();
        runCatalogSync.scheduleDue();

        assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE integration_id = :i AND idempotency_key LIKE :key")
                .param("i", integration).param("key", integration + ":2%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void should_guard_run_by_role_flow_and_tenant() {
        UUID catalog = createIntegration("CATALOG", posConnection, giftHubConnection);
        UUID order = orderIntegration();
        perform(MockMvcRequestBuilders.post("/api/admin/integrations/{id}/run", catalog).header("Authorization", bearer(tokenFor(Role.VIEWER, tenant))), 403);
        JsonNode mismatch = body(perform(MockMvcRequestBuilders.post("/api/admin/integrations/{id}/run", order).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 422));
        assertThat(mismatch.get("code").asString()).isEqualTo("flow-mismatch");

        UUID otherTenant = createTenant(admin);
        perform(MockMvcRequestBuilders.post("/api/admin/integrations/{id}/run", catalog)
                .header("Authorization", bearer(tokenFor(Role.TENANT_ADMIN, otherTenant))), 404);
        perform(MockMvcRequestBuilders.post("/api/admin/integrations/{id}/run", catalog)
                .header("Authorization", bearer(tokenFor(Role.OPERATOR, tenant))), 202);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'integration.run' AND resource_id = :id")
                .param("id", catalog.toString()).query(Long.class).single()).isEqualTo(1);
    }

    private Operation runNow(UUID integration) {
        JsonNode started = body(perform(MockMvcRequestBuilders.post("/api/admin/integrations/{id}/run", integration).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)), 202));
        return reload(UUID.fromString(started.get("operationId").asString()));
    }

    private void stubCatalogPages() {
        for (int page = 1; page <= 3; page++) {
            stubPos(get(urlPathEqualTo(pos("/api/integration/catalog"))).withQueryParam("page", equalTo(String.valueOf(page)))
                    .withHeader("X-Api-Key", equalTo("pv_live_test"))
                    .willReturn(okJson(catalogPage(page, (page - 1) * 500 + 1, Math.min(page * 500, TOTAL)))));
        }
    }

    /** Items {@code from..to} (1-based) of a {@value #TOTAL}-product catalog; the last one has no stock. */
    private String catalogPage(int page, int from, int to) {
        List<Map<String, Object>> items = new ArrayList<>();
        IntStream.rangeClosed(from, to).forEach(n -> {
            Map<String, Object> item = new HashMap<>();
            item.put("code", "P-%04d".formatted(n));
            item.put("name", "Producto " + n);
            item.put("price", 1100);
            item.put("stockQty", n == TOTAL ? 0 : 3.5);
            item.put("tracked", true);
            item.put("category", "Panificados");
            item.put("imageUrl", "/uploads/p.png");
            item.put("updatedAt", "2026-10-01T00:00:00Z");
            items.add(item);
        });
        return toJson(Map.of("items", items, "page", page, "limit", 500, "total", from == 1 && to == 1 ? 1 : TOTAL));
    }

    /** Answers "created" for every product of the request (WireMock response template). */
    private static String bulkResponse() {
        return "{\"results\":[{{#each (jsonPath request.body '$.products') as |p|}}{{#unless @first}},{{/unless}}"
                + "{\"external_id\":\"{{p.external_id}}\",\"result\":\"created\",\"product_id\":\"gh-{{p.external_id}}\"}{{/each}}]}";
    }
}
