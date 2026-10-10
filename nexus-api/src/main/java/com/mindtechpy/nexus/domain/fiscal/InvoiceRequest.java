package com.mindtechpy.nexus.domain.fiscal;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical request to issue a fiscal document (spec §8.1). Money in whole PYG, VAT included.
 * {@code sourceRef} is the idempotency key in Nexus and the {@code transactionId} in SIFEN; the issuer
 * never travels: SIFEN takes it from the credential. Invalid requests fail here, before anything is
 * persisted or sent, so they never consume SIFEN numbering.
 */
public record InvoiceRequest(String sourceRef, String documentType, String issuedAt, String branchRef,
                             String pointOfSaleRef, String referenceCdc, String issueReasonCode, Header header,
                             Customer customer, List<Item> items, Long globalDiscount) {

    public static final Pattern SOURCE_REF = Pattern.compile("^[A-Za-z0-9:_-]{1,100}$");
    private static final Set<String> DOCUMENT_TYPES = Set.of("INVOICE", "CREDIT_NOTE", "DEBIT_NOTE");
    private static final Set<String> PAYMENT_CONDITIONS = Set.of("CASH", "CREDIT");
    private static final Set<String> VAT_RATES = Set.of("0", "5", "10");

    public record Header(String currency, String paymentCondition, String paymentMethod) {
    }

    public record Customer(String documentType, String documentNumber, String name, String email) {
    }

    public record Item(String code, String description, String quantity, Long unitPrice, Long discount,
                       String vatRate, String unit) {
    }

    public InvoiceRequest {
        require(sourceRef != null && SOURCE_REF.matcher(sourceRef).matches(),
                "sourceRef debe tener 1-100 caracteres [A-Za-z0-9:_-]");
        require(DOCUMENT_TYPES.contains(documentType), "documentType debe ser INVOICE, CREDIT_NOTE o DEBIT_NOTE");
        require(isOffsetDateTime(issuedAt), "issuedAt debe ser ISO-8601 con offset");
        if (!documentType.equals("INVOICE")) {
            require(present(referenceCdc), "referenceCdc es obligatorio en notas de crédito y débito");
            require(isReasonCode(issueReasonCode), "issueReasonCode (1-8) es obligatorio en notas de crédito y débito");
        }
        require(header != null && present(header.currency()) && present(header.paymentMethod())
                && PAYMENT_CONDITIONS.contains(header.paymentCondition()),
                "header requiere currency, paymentMethod y paymentCondition CASH o CREDIT");
        require(customer != null && present(customer.documentType()) && present(customer.documentNumber())
                && present(customer.name()), "customer requiere documentType, documentNumber y name");
        require(items != null && !items.isEmpty(), "items no puede estar vacío");
        items.forEach(InvoiceRequest::validate);
        require(globalDiscount == null || globalDiscount >= 0, "globalDiscount no puede ser negativo");
        items = List.copyOf(items);
    }

    private static void validate(Item item) {
        require(item != null && present(item.code()) && present(item.description()),
                "cada item requiere code y description");
        require(isPositiveDecimal(item.quantity()), "quantity debe ser un decimal mayor a 0");
        require(item.unitPrice() != null && item.unitPrice() > 0, "unitPrice debe ser mayor a 0");
        require(item.discount() == null || item.discount() >= 0, "discount no puede ser negativo");
        require(VAT_RATES.contains(item.vatRate()), "vatRate debe ser 0, 5 o 10");
    }

    private static boolean isOffsetDateTime(String value) {
        try {
            OffsetDateTime.parse(value);
            return true;
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }

    private static boolean isReasonCode(String value) {
        return value != null && value.matches("^[1-8]$");
    }

    private static boolean isPositiveDecimal(String value) {
        try {
            return value != null && new BigDecimal(value).signum() > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw DomainException.invalid("invalid-invoice", message);
        }
    }
}
