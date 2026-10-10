package com.mindtechpy.nexus.application.saga.fiscal;

import com.mindtechpy.nexus.application.port.out.SifenPort;
import com.mindtechpy.nexus.application.saga.Saga;
import com.mindtechpy.nexus.application.saga.StepContext;
import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.fiscal.ProviderStatus;
import com.mindtechpy.nexus.domain.fiscal.SifenDocument;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;

import java.time.Duration;
import java.util.Optional;

import static com.mindtechpy.nexus.application.saga.fiscal.IssueInvoiceSaga.isResend;
import static com.mindtechpy.nexus.application.saga.fiscal.IssueInvoiceSaga.log;
import static com.mindtechpy.nexus.application.saga.fiscal.IssueInvoiceSaga.missingCredentials;
import static com.mindtechpy.nexus.application.saga.fiscal.IssueInvoiceSaga.readTimeout;
import static com.mindtechpy.nexus.application.saga.fiscal.IssueInvoiceSaga.stillUnverified;

/**
 * {@code CANCEL_INVOICE}: cancellation event 01 of an issued document, keyed by its {@code sourceRef}.
 * SIFEN answers synchronously; only {@code estado=CANCELADO} proves the effect. A resend is preceded by a
 * VERIFY read that adopts a cancellation already registered.
 */
public class CancelInvoiceSaga implements Saga {

    static final String SUBMIT_CANCEL = "SUBMIT_CANCEL";

    private final SifenPort sifen;

    public CancelInvoiceSaga(SifenPort sifen) {
        this.sifen = sifen;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.CANCEL_INVOICE;
    }

    @Override
    public String firstStep() {
        return SUBMIT_CANCEL;
    }

    @Override
    public StepResult execute(StepContext context) {
        Optional<ConnectorError> missing = missingCredentials(context);
        if (missing.isPresent()) {
            return new StepResult.Failed(missing.get());
        }
        Operation op = context.operation();
        Duration timeout = readTimeout(context);
        if (isResend(op)) {
            switch (sifen.find(context.target(), op.idempotencyKey(), timeout)) {
                case SifenPort.Ok<SifenDocument>(SifenDocument doc) when isCancelled(doc) -> {
                    log(op, "VERIFY → estado=CANCELADO: se adopta, no se reenvía");
                    return new StepResult.Completed(doc.cdc(), doc.toResult());
                }
                case SifenPort.Ok<SifenDocument>(SifenDocument doc) ->
                        log(op, "VERIFY → estado=" + doc.estado() + ": se reenvía la cancelación");
                case SifenPort.NotFound<SifenDocument>() -> {
                    return new StepResult.Failed(notFound());
                }
                case SifenPort.Failed<SifenDocument>(ConnectorError error) -> {
                    log(op, "VERIFY falló " + error.kind() + ":" + error.code() + ": no se reenvía sin verificar");
                    return new StepResult.Failed(stillUnverified(error));
                }
            }
        }
        String reason = (String) op.payload().get("reason");
        return switch (sifen.cancel(context.target(), op.idempotencyKey(), reason, timeout)) {
            case SifenPort.Ok<SifenDocument>(SifenDocument doc) when isCancelled(doc) -> {
                log(op, "SUBMIT_CANCEL → 200 estado=CANCELADO cdc=" + doc.cdc());
                yield new StepResult.Completed(doc.cdc(), doc.toResult());
            }
            case SifenPort.Ok<SifenDocument>(SifenDocument doc) -> {
                log(op, "SUBMIT_CANCEL → 200 estado=" + doc.estado() + ": cancelación no confirmada");
                yield new StepResult.Failed(ConnectorError.of(ErrorKind.UNCERTAIN, "invalid-response",
                        "SIFEN respondió 200 sin estado CANCELADO"));
            }
            case SifenPort.NotFound<SifenDocument>() -> new StepResult.Failed(notFound());
            case SifenPort.Failed<SifenDocument>(ConnectorError error) -> {
                log(op, "SUBMIT_CANCEL falló " + error.kind() + ":" + error.code());
                yield new StepResult.Failed(error);
            }
        };
    }

    /** Nothing to undo: a failed cancellation leaves the document as it was, which is what the POS expects. */
    @Override
    public void compensate(StepContext context, ConnectorError cause) {
        log(context.operation(), "compensación: sin efecto remoto que deshacer (el DE sigue vigente) causa="
                + cause.kind() + ":" + cause.code());
    }

    private static boolean isCancelled(SifenDocument doc) {
        return doc.isValid() && doc.status() == ProviderStatus.CANCELADO;
    }

    private static ConnectorError notFound() {
        return ConnectorError.of(ErrorKind.REJECTED, "invoice-not-found", "SIFEN no tiene el documento a cancelar");
    }
}
