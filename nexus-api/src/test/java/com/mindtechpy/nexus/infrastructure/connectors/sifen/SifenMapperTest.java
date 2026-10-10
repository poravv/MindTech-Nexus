package com.mindtechpy.nexus.infrastructure.connectors.sifen;

import com.mindtechpy.nexus.domain.fiscal.InvoiceRequest;
import com.mindtechpy.nexus.domain.fiscal.SifenDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Canonical fixtures against the request examples of {@code electronico-sifen-backend/examples}. */
class SifenMapperTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void should_produce_the_sifen_example_without_issuer_when_mapping_a_consumer_invoice() {
        assertMapsTo("invoice-consumidor-final.json", "facturar-consumidor-final.json");
    }

    @Test
    void should_send_reference_reason_and_global_discount_when_mapping_a_credit_note() {
        assertMapsTo("invoice-nota-credito.json", "facturar-nota-credito.json");
    }

    @Test
    void should_compose_the_full_document_number_when_sifen_reports_establishment_and_point() {
        SifenDocument document = SifenMapper.toDocument(new SifenMapper.FacturaResponse("0180", "APROBADO", "001", "002",
                "0000123", "0260", "Aprobado", new BigDecimal("4400.00"), "2026-10-10T17:05:00Z"));

        assertThat(document.documentNumber()).isEqualTo("001-002-0000123");
        assertThat(document.total()).isEqualTo(4400L);
        assertThat(document.isValid()).isTrue();
    }

    private void assertMapsTo(String canonicalFixture, String sifenFixture) {
        InvoiceRequest request = json.readValue(fixture(canonicalFixture), InvoiceRequest.class);

        JsonNode mapped = json.readTree(json.writeValueAsString(SifenMapper.toFacturarRequest(request)));

        assertThat(mapped).isEqualTo(json.readTree(fixture(sifenFixture)));
        assertThat(mapped.has("emisor")).isFalse();
        assertThat(mapped.has("emisorId")).isFalse();
    }

    private static InputStream fixture(String name) {
        return SifenMapperTest.class.getResourceAsStream("/sifen/" + name);
    }
}
