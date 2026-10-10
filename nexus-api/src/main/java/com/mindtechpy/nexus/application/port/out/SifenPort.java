package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;
import com.mindtechpy.nexus.domain.fiscal.SifenDocument;
import com.mindtechpy.nexus.domain.operation.ConnectorError;

import java.time.Duration;
import java.util.Map;

/**
 * electronico-sifen API of one issuer (the endpoint's credentials). Every call is made once, signed, and
 * returns a classified outcome instead of throwing; the caller decides what to do with it.
 */
public interface SifenPort {

    sealed interface Result<T> {
    }

    record Ok<T>(T value) implements Result<T> {
    }

    /** SIFEN does not know the transaction or document. */
    record NotFound<T>() implements Result<T> {
    }

    record Failed<T>(ConnectorError error) implements Result<T> {
    }

    /**
     * {@code POST /api/v1/facturas}. Idempotent in SIFEN by {@code transactionId} (= {@code sourceRef}) and
     * payload hash.
     *
     * @param invoice canonical request ({@code InvoiceRequest}) as stored in the operation payload
     */
    Result<SifenDocument> submit(Endpoint sifen, Map<String, Object> invoice, Duration readTimeout);

    /** {@code GET /api/v1/facturas/{transactionId}}: the read that verifies before any resend. */
    Result<SifenDocument> find(Endpoint sifen, String transactionId, Duration readTimeout);

    /** {@code POST /api/v1/facturas/{transactionId}/cancelar}: cancellation event 01. */
    Result<SifenDocument> cancel(Endpoint sifen, String transactionId, String reason, Duration readTimeout);

    /** {@code GET /api/v1/kude/{cdc}?template=}: the PDF, never stored. */
    Result<byte[]> kude(Endpoint sifen, String cdc, String template);
}
