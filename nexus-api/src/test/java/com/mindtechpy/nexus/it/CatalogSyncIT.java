package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mindtechpy.nexus.application.usecase.RunCatalogSync;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.operation.OperationStep;
import com.mindtechpy.nexus.domain.shared.TenantId;
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
    void should_archive_in_gifthub_the_mapped_products_the_pos_no_longer_lists() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        insertProductMapping("P-0001");
        insertProductMapping("P-RETIRADO");
        stubPos(get(urlPathEqualTo(pos("/api/integration/catalog"))).willReturn(okJson(catalogPage(1, 1, 1))));
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).willReturn(okJson(bulkResponse()).withTransformers("response-template")));

        Operation op = runNow(integration);
        drain();

        Operation done = reload(op.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("archived", 1).containsEntry("archiveErrors", 0);
        var bulks = giftHubRequests("PUT", "/products/bulk");
        assertThat(bulks).hasSize(2);
        JsonNode archived = jsonOf(bulks.get(1)).get("products");
        assertThat(archived).hasSize(1);
        assertThat(archived.get(0).get("external_id").asString()).isEqualTo("P-RETIRADO");
        assertThat(archived.get(0).get("archived").asBoolean()).isTrue();
        assertThat(productMappings()).containsExactly("P-0001");
    }

    @Test
    void should_archive_one_chunk_per_step_until_every_missing_product_is_off_sale() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        insertProductMapping("P-0001");
        IntStream.rangeClosed(1, 501).forEach(n -> insertProductMapping("P-RETIRADO-%03d".formatted(n)));
        stubPos(get(urlPathEqualTo(pos("/api/integration/catalog"))).willReturn(okJson(catalogPage(1, 1, 1))));
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).willReturn(okJson(bulkResponse()).withTransformers("response-template")));

        Operation op = runNow(integration);
        drain();

        Operation done = reload(op.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("archived", 501).containsEntry("archiveErrors", 0);
        assertThat(giftHubRequests("PUT", "/products/bulk")).extracting(r -> jsonOf(r).get("products").size())
                .containsExactly(1, 500, 1);
        assertThat(operations.findSteps(new TenantId(tenant), op.id())).extracting(OperationStep::step)
                .filteredOn("ARCHIVE_MISSING"::equals).hasSize(2);
        assertThat(productMappings()).containsExactly("P-0001");
    }

    @Test
    void should_keep_mapping_and_report_error_when_gifthub_rejects_the_archive_of_a_product() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        insertProductMapping("P-BORRADO-EN-GIFTHUB");
        stubPos(get(urlPathEqualTo(pos("/api/integration/catalog"))).willReturn(okJson(catalogPage(1, 1, 1))));
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).withRequestBody(notContaining("\"archived\""))
                .willReturn(okJson(bulkResponse()).withTransformers("response-template")));
        stubGiftHub(put(urlPathEqualTo(gh("/products/bulk"))).withRequestBody(containing("\"archived\""))
                .willReturn(okJson("{\"results\":[{\"external_id\":\"P-BORRADO-EN-GIFTHUB\",\"result\":\"error\","
                        + "\"error\":\"name es obligatorio al crear\"}]}")));

        Operation op = runNow(integration);
        drain();

        Operation done = reload(op.id());
        assertThat(done.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(done.result()).containsEntry("archived", 0).containsEntry("archiveErrors", 1);
        assertThat(productMappings()).contains("P-BORRADO-EN-GIFTHUB");
    }

    @Test
    void should_archive_nothing_when_the_pos_catalog_comes_back_empty() {
        UUID integration = createIntegration("CATALOG", posConnection, giftHubConnection);
        insertProductMapping("P-0001");
        stubPos(get(urlPathEqualTo(pos("/api/integration/catalog")))
                .willReturn(okJson(toJson(Map.of("items", List.of(), "page", 1, "limit", 500, "total", 0)))));

        Operation op = runNow(integration);
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(giftHubRequests("PUT", "/products/bulk")).isEmpty();
        assertThat(productMappings()).containsExactly("P-0001");
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

    private void insertProductMapping(String code) {
        jdbc.sql("""
                INSERT INTO entity_mappings (id, tenant_id, connection_id, entity_type, internal_id, external_id)
                VALUES (gen_random_uuid(), :t, :c, 'PRODUCT', :code, :gh)""")
                .param("t", tenant).param("c", giftHubConnection).param("code", code).param("gh", "gh-" + code).update();
    }

    private List<String> productMappings() {
        return jdbc.sql("SELECT internal_id FROM entity_mappings WHERE tenant_id = :t AND entity_type = 'PRODUCT' ORDER BY internal_id")
                .param("t", tenant).query(String.class).list();
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
