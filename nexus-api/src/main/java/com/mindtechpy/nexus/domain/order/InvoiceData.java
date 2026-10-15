package com.mindtechpy.nexus.domain.order;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The invoice data a GiftHub customer gave for the order ({@code data.order.invoice}): the POS invoice goes to this
 * RUC instead of consumidor final. Only the shape is checked here; the POS validates the RUC against its DV.
 */
public record InvoiceData(String ruc, String dv, String legalName) {

    private static final Pattern RUC = Pattern.compile("^[0-9]{3,9}$");
    private static final Pattern DV = Pattern.compile("^[0-9]$");
    private static final int MAX_LEGAL_NAME = 150;

    /**
     * @return null when the customer did not request an invoice ({@code requested} other than {@code true})
     * @throws DomainException {@code invalid-ruc} when it was requested without a well-formed RUC, DV and legal name
     */
    public static InvoiceData from(Object invoice) {
        if (!(invoice instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("requested"))) {
            return null;
        }
        String ruc = text(map, "ruc");
        String dv = text(map, "dv");
        String legalName = text(map, "legal_name");
        if (ruc == null || !RUC.matcher(ruc).matches() || dv == null || !DV.matcher(dv).matches() || legalName == null) {
            throw DomainException.invalid("invalid-ruc", "La factura pedida no trae RUC (3 a 9 dígitos), DV (1 dígito) y razón social");
        }
        return new InvoiceData(ruc, dv, legalName.length() <= MAX_LEGAL_NAME ? legalName : legalName.substring(0, MAX_LEGAL_NAME).strip());
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }
}
