package com.mindtechpy.nexus.infrastructure.connectors.sifen;

import com.mindtechpy.nexus.domain.fiscal.InvoiceRequest;
import com.mindtechpy.nexus.domain.fiscal.SifenDocument;
import com.mindtechpy.nexus.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void should_send_installments_in_the_header_currency_and_the_down_payment_when_mapping_a_credit_invoice() {
        assertMapsTo("invoice-credito-cuotas.json", "facturar-credito-cuotas.json");
    }

    @ParameterizedTest
    @CsvSource({"2, 2000, 1000", "3, 3000, 1000", "3, 2001, 667", "1, 2000, 2000"})
    void should_send_the_line_discount_per_unit_when_mapping_an_item(String quantity, long lineDiscount, long perUnit) {
        JsonNode detail = json.readTree(json.writeValueAsString(SifenMapper.toFacturarRequest(invoiceWith(
                new InvoiceRequest.Item("PAN-001", "Chipa", quantity, 10000L, lineDiscount, "10", "UNI"))))).get("detail").get(0);

        assertThat(detail.get("descuento").isIntegralNumber()).isTrue();
        assertThat(detail.get("descuento").asLong()).isEqualTo(perUnit);
        long sifenLine = (10000 - detail.get("descuento").asLong()) * Long.parseLong(quantity);
        assertThat(sifenLine).isEqualTo(10000 * Long.parseLong(quantity) - lineDiscount);
    }

    @Test
    void should_reject_a_line_discount_that_does_not_split_into_whole_guaranies_per_unit() {
        assertThatThrownBy(() -> invoiceWith(new InvoiceRequest.Item("PAN-001", "Chipa", "3", 10000L, 2000L, "10", "UNI")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("el más cercano es 2001")
                .extracting("code").isEqualTo("discount-not-divisible");
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

    private static InvoiceRequest invoiceWith(InvoiceRequest.Item item) {
        return new InvoiceRequest("ref-1", "INVOICE", "2026-10-10T14:05:00-03:00", null, null, null, null,
                new InvoiceRequest.Header("PYG", "CASH", "EFECTIVO"),
                new InvoiceRequest.Customer("INNOMINADO", "0", "CONSUMIDOR FINAL", null), List.of(item), null, null);
    }

    private static InputStream fixture(String name) {
        return SifenMapperTest.class.getResourceAsStream("/sifen/" + name);
    }
}
