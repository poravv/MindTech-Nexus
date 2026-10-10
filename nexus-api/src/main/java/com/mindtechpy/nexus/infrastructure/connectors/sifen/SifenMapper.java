package com.mindtechpy.nexus.infrastructure.connectors.sifen;

import com.mindtechpy.nexus.domain.fiscal.InvoiceRequest;
import com.mindtechpy.nexus.domain.fiscal.SifenDocument;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical invoice ↔ electronico-sifen ({@code FacturarTransaccionRequest} / {@code FacturaComercialResponse}).
 * The issuer is never sent: SIFEN takes it, with its timbrado and numbering, from the API key. Branch and
 * point of issue come only from the request ({@code branchRef} → {@code sucursalId}, {@code pointOfSaleRef} →
 * {@code puntoExpedicionId}); when absent SIFEN uses the issuer's default ones. Null fields are omitted, so
 * the same canonical request always produces the same bytes (SIFEN dedupes by payload hash). {@code credit} becomes
 * the top-level {@code credito}: {@code termDays} → condición 1 (plazo), {@code installments} → condición 2 (cuotas
 * in the header currency, as SIFEN requires).
 */
final class SifenMapper {

    private static final Map<String, String> DOCUMENT_TYPES = Map.of("INVOICE", "FACTURA",
            "CREDIT_NOTE", "NOTA_CREDITO", "DEBIT_NOTE", "NOTA_DEBITO");
    private static final Map<String, String> PAYMENT_CONDITIONS = Map.of("CASH", "CONTADO", "CREDIT", "CREDITO");

    /** Response body of {@code /facturas} endpoints; unknown fields are ignored. */
    record FacturaResponse(String cdc, String estado, String establecimiento, String puntoExpedicion,
                           String numeroDocumento, String codigoRespuesta, String mensajeRespuesta, BigDecimal total,
                           String actualizadoEn) {
    }

    private SifenMapper() {
    }

    static Map<String, Object> toFacturarRequest(InvoiceRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId", request.sourceRef());
        body.put("fechaEmision", request.issuedAt());
        body.put("tipoDocumento", DOCUMENT_TYPES.get(request.documentType()));
        putIfPresent(body, "sucursalId", request.branchRef());
        putIfPresent(body, "puntoExpedicionId", request.pointOfSaleRef());
        putIfPresent(body, "cdcReferencia", request.referenceCdc());
        putIfPresent(body, "motivoEmision", request.issueReasonCode() == null ? null : Integer.valueOf(request.issueReasonCode()));

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("moneda", request.header().currency());
        header.put("condicionPago", PAYMENT_CONDITIONS.get(request.header().paymentCondition()));
        header.put("formaPago", request.header().paymentMethod());
        body.put("header", header);

        Map<String, Object> receptor = new LinkedHashMap<>();
        receptor.put("tipoDocumento", request.customer().documentType());
        receptor.put("numeroDocumento", request.customer().documentNumber());
        receptor.put("razonSocial", request.customer().name());
        putIfPresent(receptor, "email", request.customer().email());
        body.put("receptor", receptor);

        body.put("detail", request.items().stream().map(SifenMapper::toDetail).toList());
        if (request.credit() != null) {
            body.put("credito", toCredito(request.credit(), request.header().currency()));
        }
        if (request.globalDiscount() != null && request.globalDiscount() > 0) {
            body.put("descuentoGlobal", Map.of("tipo", "MONTO", "valor", request.globalDiscount()));
        }
        return body;
    }

    static SifenDocument toDocument(FacturaResponse response) {
        boolean hasFullNumber = response.establecimiento() != null && response.puntoExpedicion() != null
                && response.numeroDocumento() != null;
        String documentNumber = hasFullNumber
                ? response.establecimiento() + "-" + response.puntoExpedicion() + "-" + response.numeroDocumento()
                : response.numeroDocumento();
        return new SifenDocument(response.cdc(), response.estado(), documentNumber, response.codigoRespuesta(),
                response.mensajeRespuesta(), response.total() == null ? null : response.total().longValue(),
                response.actualizadoEn());
    }

    private static Map<String, Object> toCredito(InvoiceRequest.Credit credit, String currency) {
        Map<String, Object> credito = new LinkedHashMap<>();
        if (credit.termDays() != null) {
            credito.put("condicion", 1);
            credito.put("plazo", credit.termDays() + " dias");
        } else {
            credito.put("condicion", 2);
            credito.put("cuotas", credit.installments().stream().map(installment -> {
                Map<String, Object> cuota = new LinkedHashMap<>();
                cuota.put("moneda", currency);
                cuota.put("monto", installment.amount());
                putIfPresent(cuota, "vencimiento", installment.dueDate());
                return cuota;
            }).toList());
        }
        putIfPresent(credito, "montoEntrega", credit.downPayment());
        return credito;
    }

    private static Map<String, Object> toDetail(InvoiceRequest.Item item) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("codigo", item.code());
        detail.put("descripcion", item.description());
        detail.put("cantidad", new BigDecimal(item.quantity()));
        detail.put("precioUnitario", item.unitPrice());
        putIfPresent(detail, "descuento", item.discount());
        detail.put("ivaTipo", item.vatRate());
        putIfPresent(detail, "unidadMedida", item.unit());
        return detail;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
