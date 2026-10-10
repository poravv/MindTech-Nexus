package com.mindtechpy.nexus.domain.fiscal;

import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;

import java.util.Arrays;
import java.util.Optional;

/** State of an electronic document as SIFEN reports it ({@code estado}) and its canonical status (spec §8.1). */
public enum ProviderStatus {
    PENDIENTE_ENVIO(InvoiceStatus.SUBMITTED),
    EN_PROCESO(InvoiceStatus.SUBMITTED),
    APROBADO(InvoiceStatus.APPROVED),
    APROBADO_CON_OBSERVACION(InvoiceStatus.APPROVED_WITH_NOTES),
    RECHAZADO(InvoiceStatus.REJECTED),
    CANCELADO(InvoiceStatus.CANCELLED);

    /** HTTP status recorded on a rejection by SIFEN: the request was processed and the document rejected. */
    private static final int PROCESSED = 200;

    private final InvoiceStatus invoiceStatus;

    ProviderStatus(InvoiceStatus invoiceStatus) {
        this.invoiceStatus = invoiceStatus;
    }

    public InvoiceStatus invoiceStatus() {
        return invoiceStatus;
    }

    /** SIFEN may still change it: the document has to be tracked. */
    public boolean isPending() {
        return invoiceStatus == InvoiceStatus.SUBMITTED;
    }

    public static Optional<ProviderStatus> parse(String estado) {
        return Arrays.stream(values()).filter(status -> status.name().equals(estado)).findFirst();
    }

    /** Error that ends an operation whose document SIFEN rejected, keeping SIFEN's response code and message. */
    public static ConnectorError rejection(String codigoRespuesta, String mensajeRespuesta) {
        return new ConnectorError(ErrorKind.REJECTED, codigoRespuesta, mensajeRespuesta, PROCESSED, null);
    }

    /** True for errors built by {@link #rejection}, as opposed to requests SIFEN refused to process. */
    public static boolean isRejection(ConnectorError error) {
        return error != null && error.kind() == ErrorKind.REJECTED && Integer.valueOf(PROCESSED).equals(error.httpStatus());
    }
}
