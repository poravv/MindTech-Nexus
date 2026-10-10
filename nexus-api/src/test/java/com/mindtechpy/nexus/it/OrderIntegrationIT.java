package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.connection.ConnectorType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** An ORDER integration (GiftHub → POS) only makes sense with the CATALOG that pushes that POS's codes to that GiftHub. */
class OrderIntegrationIT extends AbstractConnectorIT {

    @Test
    void should_reject_with_422_an_order_integration_whose_gifthub_gets_no_catalog_from_its_pos() {
        createIntegration("CATALOG", posConnection, giftHubConnection);
        UUID otherGiftHub = insertConnection(ConnectorType.GIFTHUB, GIFTHUB,
                Map.of("apiKey", "gh_live_other", "webhookSecret", "otro-secreto"), Map.of());

        JsonNode problem = body(perform(post("/api/admin/integrations").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("flow", "ORDER", "code", "pedidos-sin-catalogo",
                        "sourceConnectionId", otherGiftHub.toString(), "targetConnectionId", posConnection.toString()))), 422));

        assertThat(problem.get("code").asString()).isEqualTo("catalog-integration-missing");
    }

    @Test
    void should_reject_with_422_moving_an_order_integration_to_a_gifthub_without_catalog() {
        UUID order = orderIntegration();
        UUID otherGiftHub = insertConnection(ConnectorType.GIFTHUB, GIFTHUB,
                Map.of("apiKey", "gh_live_other", "webhookSecret", "otro-secreto"), Map.of());

        JsonNode problem = body(perform(put("/api/admin/integrations/{id}", order).param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", "pedidos-movido", "sourceConnectionId", otherGiftHub.toString(),
                        "targetConnectionId", posConnection.toString()))), 422));

        assertThat(problem.get("code").asString()).isEqualTo("catalog-integration-missing");
    }
}
