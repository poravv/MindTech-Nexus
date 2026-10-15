package com.mindtechpy.nexus.application.saga.catalog;

import com.mindtechpy.nexus.application.port.out.PosPort.CatalogItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** POS catalog item → GiftHub product mapping. */
class CatalogSyncSagaTest {

    @Test
    void should_forward_the_pos_price_label() {
        assertThat(CatalogSyncSaga.product(item("por porción")).priceLabel()).isEqualTo("por porción");
    }

    @Test
    void should_send_null_price_label_when_the_pos_has_none_or_blank() {
        assertThat(CatalogSyncSaga.product(item(null)).priceLabel()).isNull();
        assertThat(CatalogSyncSaga.product(item("   ")).priceLabel()).isNull();
    }

    @Test
    void should_cut_the_price_label_to_the_gifthub_limit() {
        String label = "x".repeat(41);
        assertThat(CatalogSyncSaga.product(item(label)).priceLabel()).isEqualTo("x".repeat(40));
    }

    @Test
    void should_forward_the_pos_description() {
        assertThat(CatalogSyncSaga.product(withDescription("Torta de chocolate")).description()).isEqualTo("Torta de chocolate");
    }

    @Test
    void should_send_null_description_when_the_pos_has_none_or_blank() {
        assertThat(CatalogSyncSaga.product(withDescription(null)).description()).isNull();
        assertThat(CatalogSyncSaga.product(withDescription("   ")).description()).isNull();
    }

    @Test
    void should_cut_the_description_to_the_gifthub_limit() {
        assertThat(CatalogSyncSaga.product(withDescription("d".repeat(1001))).description()).isEqualTo("d".repeat(1000));
    }

    private static CatalogItem item(String priceLabel) {
        return new CatalogItem("P-1", "Torta", null, new BigDecimal("1100"), new BigDecimal("3.5"), true, "Panificados",
                "https://pos/p.png", priceLabel);
    }

    private static CatalogItem withDescription(String description) {
        return new CatalogItem("P-1", "Torta", description, new BigDecimal("1100"), new BigDecimal("3.5"), true, "Panificados",
                "https://pos/p.png", null);
    }
}
