package com.mindtechpy.nexus.domain.fiscal;

import com.mindtechpy.nexus.domain.shared.DomainException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical request to issue a fiscal document (spec §8.1). Money in whole PYG, VAT included.
 * {@code sourceRef} is the idempotency key in Nexus and the {@code transactionId} in SIFEN; the issuer
 * never travels: SIFEN takes it from the credential. Invalid requests fail here, before anything is
 * persisted or sent, so they never consume SIFEN numbering. A credit invoice carries {@code credit}: a term in
 * days or the installments, plus the optional down payment; SIFEN checks that installments cover the balance.
 */
public record InvoiceRequest(String sourceRef, String documentType, String issuedAt, String branchRef,
                             String pointOfSaleRef, String referenceCdc, String issueReasonCode, Header header,
                             Customer customer, List<Item> items, Long globalDiscount, Credit credit) {

    public static final Pattern SOURCE_REF = Pattern.compile("^[A-Za-z0-9:_-]{1,100}$");
    private static final Set<String> DOCUMENT_TYPES = Set.of("INVOICE", "CREDIT_NOTE", "DEBIT_NOTE");
    private static final Set<String> PAYMENT_CONDITIONS = Set.of("CASH", "CREDIT");
    private static final Set<String> VAT_RATES = Set.of("0", "5", "10");
    /** Variation SIFEN admits between a per-unit discount and its amount (rule 1862, NT 001). */
    private static final BigDecimal SIFEN_TOLERANCE = new BigDecimal("0.8");

    public record Header(String currency, String paymentCondition, String paymentMethod) {
    }

    public record Customer(String documentType, String documentNumber, String name, String email) {
    }

    /**
     * {@code discount} is the discount of the whole line (what the POS computes), not per unit; SIFEN takes it
     * per unit ({@link #unitDiscount()}), so the line total stays {@code quantity × unitPrice − discount}.
     */
    public record Item(String code, String description, String quantity, Long unitPrice, Long discount,
                       String vatRate, String unit) {

        public boolean hasWholeQuantity() {
            return new BigDecimal(quantity).stripTrailingZeros().scale() <= 0;
        }

        /** The line discount spread per unit, with the 8 decimals SIFEN keeps; null without discount. */
        public BigDecimal unitDiscount() {
            return discount == null ? null
                    : BigDecimal.valueOf(discount).divide(new BigDecimal(quantity), 8, RoundingMode.HALF_UP);
        }
    }

    /** Exactly one of {@code termDays} or {@code installments}. */
    public record Credit(Integer termDays, List<Installment> installments, Long downPayment) {
    }

    /** {@code dueDate} is an ISO date (yyyy-MM-dd), optional as in SIFEN. */
    public record Installment(Long amount, String dueDate) {
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
        items.forEach(item -> validate(item, header.currency()));
        require(globalDiscount == null || globalDiscount >= 0, "globalDiscount no puede ser negativo");
        items = List.copyOf(items);
        if (documentType.equals("INVOICE") && header.paymentCondition().equals("CREDIT")) {
            require(credit != null, "credit es obligatorio en facturas con paymentCondition CREDIT");
            validate(credit);
        } else {
            require(credit == null, "credit solo se admite en facturas (INVOICE) con paymentCondition CREDIT");
        }
    }

    private static void validate(Item item, String currency) {
        require(item != null && present(item.code()) && present(item.description()),
                "cada item requiere code y description");
        require(isPositiveDecimal(item.quantity()), "quantity debe ser un decimal mayor a 0");
        require(item.unitPrice() != null && item.unitPrice() > 0, "unitPrice debe ser mayor a 0");
        require(item.discount() == null || item.discount() >= 0, "discount no puede ser negativo");
        require(VAT_RATES.contains(item.vatRate()), "vatRate debe ser 0, 5 o 10");
        if (item.discount() != null && item.discount() > 0) {
            validateDiscount(item, currency);
        }
    }

    /**
     * SIFEN prints the per-unit discount rounded (whole guaraníes in PYG) and rebuilds the line from it. A whole
     * quantity always fits: the SIFEN adapter splits the line in two when the discount does not divide evenly. A
     * fractional quantity (kg) cannot be split, so its line must match the POS line within SIFEN's tolerance, or the
     * document total would not be the sale's.
     */
    private static void validateDiscount(Item item, String currency) {
        BigDecimal quantity = new BigDecimal(item.quantity());
        BigDecimal discount = BigDecimal.valueOf(item.discount());
        if (discount.compareTo(quantity.multiply(BigDecimal.valueOf(item.unitPrice()))) > 0) {
            throw DomainException.invalid("invalid-invoice", "discount del item " + item.code() + " supera quantity × unitPrice");
        }
        if (item.hasWholeQuantity()) {
            return;
        }
        BigDecimal printed = "PYG".equals(currency) ? item.unitDiscount().setScale(0, RoundingMode.HALF_UP) : item.unitDiscount();
        if (printed.multiply(quantity).subtract(discount).abs().compareTo(SIFEN_TOLERANCE) > 0) {
            throw DomainException.invalid("discount-not-divisible", "El descuento " + discount.toPlainString() + " del item "
                    + item.code() + " no se reparte por unidad entre la cantidad fraccionaria " + item.quantity() + " sin desviarse más de "
                    + SIFEN_TOLERANCE.toPlainString() + " (SIFEN lo expresa por unidad); el más cercano es "
                    + printed.multiply(quantity).stripTrailingZeros().toPlainString());
        }
    }

    private static void validate(Credit credit) {
        require((credit.termDays() == null) != (credit.installments() == null),
                "credit requiere termDays o installments (uno de los dos)");
        require(credit.termDays() == null || (credit.termDays() >= 1 && credit.termDays() <= 999),
                "termDays debe estar entre 1 y 999");
        require(credit.installments() == null || (!credit.installments().isEmpty() && credit.installments().size() <= 999),
                "installments debe tener entre 1 y 999 cuotas");
        if (credit.installments() != null) {
            credit.installments().forEach(installment -> require(installment != null && installment.amount() != null
                    && installment.amount() > 0 && (installment.dueDate() == null || isDate(installment.dueDate())),
                    "cada cuota requiere amount mayor a 0 y dueDate opcional yyyy-MM-dd"));
        }
        require(credit.downPayment() == null || credit.downPayment() >= 0, "downPayment no puede ser negativo");
    }

    private static boolean isDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
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
