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
    @CsvSource({"2, 2000, 1000", "3, 3000, 1000", "3, 2001, 667", "1, 2000, 2000", "3, 0, 0"})
    void should_send_one_line_with_the_discount_per_unit_when_it_divides_evenly(String quantity, long lineDiscount,
                                                                                 long perUnit) {
        JsonNode detail = detailOf(new InvoiceRequest.Item("PAN-001", "Chipa", quantity, 10000L, lineDiscount, "10", "UNI"));

        assertThat(detail).hasSize(1);
        assertThat(detail.get(0).get("cantidad").asLong()).isEqualTo(Long.parseLong(quantity));
        assertThat(detail.get(0).get("descuento").isIntegralNumber()).isTrue();
        assertThat(detail.get(0).get("descuento").asLong()).isEqualTo(perUnit);
        assertThat(netTotal(detail)).isEqualTo(10000L * Long.parseLong(quantity) - lineDiscount);
    }

    @ParameterizedTest
    @CsvSource({"3, 1000, 2, 333, 1, 334", "7, 100, 5, 14, 2, 15", "3, 2000, 1, 666, 2, 667", "4, 39999, 1, 9999, 3, 10000"})
    void should_split_the_line_in_two_when_the_discount_does_not_divide_by_a_whole_quantity(
            String quantity, long lineDiscount, long baseUnits, long baseDiscount, long extraUnits, long extraDiscount) {
        JsonNode detail = detailOf(new InvoiceRequest.Item("PAN-001", "Chipa", quantity, 10000L, lineDiscount, "10", "UNI"));

        assertThat(detail).hasSize(2);
        assertThat(detail.get(0).get("cantidad").asLong()).isEqualTo(baseUnits);
        assertThat(detail.get(0).get("descuento").asLong()).isEqualTo(baseDiscount);
        assertThat(detail.get(1).get("cantidad").asLong()).isEqualTo(extraUnits);
        assertThat(detail.get(1).get("descuento").asLong()).isEqualTo(extraDiscount);
        for (JsonNode line : detail) {
            assertThat(line.get("codigo").asString()).isEqualTo("PAN-001");
            assertThat(line.get("descripcion").asString()).isEqualTo("Chipa");
            assertThat(line.get("precioUnitario").asLong()).isEqualTo(10000L);
            assertThat(line.get("ivaTipo").asString()).isEqualTo("10");
        }
        assertThat(netTotal(detail)).isEqualTo(10000L * Long.parseLong(quantity) - lineDiscount);
    }

    @Test
    void should_keep_one_line_with_the_per_unit_discount_when_a_fractional_quantity_fits_sifen_tolerance() {
        JsonNode detail = detailOf(new InvoiceRequest.Item("CAR-001", "Carne", "1.5", 10000L, 1500L, "10", "KG"));

        assertThat(detail).hasSize(1);
        assertThat(detail.get(0).get("cantidad").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(detail.get(0).get("descuento").asLong()).isEqualTo(1000L);
    }

    @Test
    void should_reject_a_fractional_quantity_whose_discount_does_not_split_into_whole_guaranies_per_unit() {
        assertThatThrownBy(() -> invoiceWith(new InvoiceRequest.Item("CAR-001", "Carne", "2.5", 10000L, 1001L, "10", "KG")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("cantidad fraccionaria 2.5")
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

    private JsonNode detailOf(InvoiceRequest.Item item) {
        return json.readTree(json.writeValueAsString(SifenMapper.toFacturarRequest(invoiceWith(item)))).get("detail");
    }

    /** How SIFEN rebuilds the document: (precioUnitario − descuento) × cantidad per line. */
    private static long netTotal(JsonNode detail) {
        long total = 0;
        for (JsonNode line : detail) {
            total += (line.get("precioUnitario").asLong() - line.get("descuento").asLong()) * line.get("cantidad").asLong();
        }
        return total;
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
