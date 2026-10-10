package com.mindtechpy.nexus.application.saga.fiscal;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.InboundEventRepository;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.System.Logger.Level.INFO;

/**
 * {@code ISSUE_INVOICE} (spec §9): {@code SUBMIT} sends the canonical request to SIFEN; a document SIFEN
 * has not decided yet goes to {@code TRACK}, which polls at 1 m, 5 m, 15 m, 1 h and then every 6 h for at
 * most 72 h (a SIFEN webhook makes the next poll run at once; one that arrives while the poll is running, and
 * so cannot reschedule it, makes the poll repeat at once instead of waiting); still pending after that, it ends
 * DEAD({@value #TRACKING_EXPIRED}) at once instead of burning retries. Any resend is preceded by a VERIFY read:
 * a failed send may still have consumed numbering in SIFEN, so the transaction is looked up first and
 * adopted when it exists; only a 404 (or a reservation without CDC, which SIFEN resolves itself) sends
 * again, always with the same {@code transactionId}.
 */
public class IssueInvoiceSaga implements Saga {

    static final String SUBMIT = "SUBMIT";
    static final String TRACK = "TRACK";
    static final List<Duration> TRACK_SCHEDULE = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6));
    static final Duration TRACK_LIMIT = Duration.ofHours(72);
    /** Dead reason of a document still pending after {@link #TRACK_LIMIT}; a SIFEN webhook revives it. */
    public static final String TRACKING_EXPIRED = "tracking-expired";

    private static final System.Logger log = System.getLogger(IssueInvoiceSaga.class.getName());

    private final SifenPort sifen;
    private final InboundEventRepository inboundEvents;
    private final ClockPort clock;

    public IssueInvoiceSaga(SifenPort sifen, InboundEventRepository inboundEvents, ClockPort clock) {
        this.sifen = sifen;
        this.inboundEvents = inboundEvents;
        this.clock = clock;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.ISSUE_INVOICE;
    }

    @Override
    public String firstStep() {
        return SUBMIT;
    }

    @Override
    public StepResult execute(StepContext context) {
        Optional<ConnectorError> missing = missingCredentials(context);
        if (missing.isPresent()) {
            return new StepResult.Failed(missing.get());
        }
        return switch (context.step()) {
            case SUBMIT -> submit(context);
            case TRACK -> track(context);
            default -> throw new IllegalStateException("Paso desconocido de ISSUE_INVOICE: " + context.step());
        };
    }

    /** Nothing to undo: an issued document is only voided by a {@code CANCEL_INVOICE} the POS asks for. */
    @Override
    public void compensate(StepContext context, ConnectorError cause) {
        log(context.operation(), "compensación: sin efecto remoto que deshacer (un DE emitido solo se anula con "
                + "CANCEL_INVOICE) causa=" + cause.kind() + ":" + cause.code());
    }

    private StepResult submit(StepContext context) {
        Operation op = context.operation();
        Duration timeout = readTimeout(context);
        if (isResend(op)) {
            SifenPort.Result<SifenDocument> found = sifen.find(context.target(), op.idempotencyKey(), timeout);
            switch (found) {
                case SifenPort.Ok<SifenDocument>(SifenDocument doc) when doc.isValid() -> {
                    log(op, "VERIFY → 200 estado=" + doc.estado() + " cdc=" + doc.cdc() + ": se adopta, no se reenvía");
                    return adopt(op, doc);
                }
                case SifenPort.Ok<SifenDocument>(SifenDocument doc) ->
                        log(op, "VERIFY → 200 sin CDC (reserva en SIFEN): se reenvía el mismo transactionId");
                case SifenPort.NotFound<SifenDocument>() ->
                        log(op, "VERIFY → 404: se reenvía el mismo transactionId");
                case SifenPort.Failed<SifenDocument>(ConnectorError error) -> {
                    log(op, "VERIFY falló " + error.kind() + ":" + error.code() + ": no se reenvía sin verificar");
                    return new StepResult.Failed(stillUnverified(error));
                }
            }
        }
        return switch (sifen.submit(context.target(), op.payload(), timeout)) {
            case SifenPort.Ok<SifenDocument>(SifenDocument doc) when doc.isValid() -> {
                log(op, "SUBMIT → 200 estado=" + doc.estado() + " cdc=" + doc.cdc());
                yield adopt(op, doc);
            }
            case SifenPort.Ok<SifenDocument>(SifenDocument doc) -> {
                log(op, "SUBMIT → 200 sin CDC o estado desconocido (" + doc.estado() + "): resultado incierto");
                yield new StepResult.Failed(ConnectorError.of(ErrorKind.UNCERTAIN, "invalid-response",
                        "SIFEN respondió 200 sin CDC o con estado desconocido"));
            }
            case SifenPort.NotFound<SifenDocument>() -> new StepResult.Failed(ConnectorError.of(ErrorKind.UNCERTAIN,
                    "unexpected-not-found", "SIFEN respondió 404 al emitir"));
            case SifenPort.Failed<SifenDocument>(ConnectorError error) -> {
                log(op, "SUBMIT falló " + error.kind() + ":" + error.code());
                yield new StepResult.Failed(error);
            }
        };
    }

    private StepResult track(StepContext context) {
        Operation op = context.operation();
        if (op.result() == null || op.result().get("cdc") == null) {
            throw new IllegalStateException("TRACK sin CDC: SUBMIT no confirmó el documento");
        }
        return switch (sifen.find(context.target(), op.idempotencyKey(), readTimeout(context))) {
            case SifenPort.Ok<SifenDocument>(SifenDocument doc) when doc.isValid() && doc.status().isPending() ->
                    scheduleNextPoll(op, doc);
            case SifenPort.Ok<SifenDocument>(SifenDocument doc) when doc.isValid() -> {
                log(op, "TRACK → estado=" + doc.estado() + ": final");
                yield adopt(op, doc);
            }
            case SifenPort.Ok<SifenDocument>(SifenDocument doc) -> new StepResult.Failed(ConnectorError.of(
                    ErrorKind.TRANSIENT, "invalid-response", "SIFEN respondió sin CDC o con estado desconocido"));
            case SifenPort.NotFound<SifenDocument>() -> new StepResult.Failed(ConnectorError.of(ErrorKind.TRANSIENT,
                    "not-found", "SIFEN no encuentra un transactionId que ya había aceptado"));
            case SifenPort.Failed<SifenDocument>(ConnectorError error) -> new StepResult.Failed(error);
        };
    }

    /** Validated document: final states end the operation, pending ones go to TRACK. */
    private StepResult adopt(Operation op, SifenDocument doc) {
        ProviderStatus status = doc.status();
        if (status == ProviderStatus.RECHAZADO) {
            return new StepResult.Failed(ProviderStatus.rejection(doc.providerCode(), doc.providerMessage()));
        }
        if (!status.isPending()) {
            return new StepResult.Completed(doc.cdc(), doc.toResult());
        }
        Map<String, Object> result = new LinkedHashMap<>(doc.toResult());
        result.put("trackingSince", clock.now().toString());
        result.put("trackPolls", 0);
        log(op, "estado=" + doc.estado() + ": TRACK en " + TRACK_SCHEDULE.getFirst());
        return new StepResult.Next(TRACK, TRACK_SCHEDULE.getFirst(), doc.cdc(), result);
    }

    private StepResult scheduleNextPoll(Operation op, SifenDocument doc) {
        Instant since = Instant.parse((String) op.result().get("trackingSince"));
        if (inboundEvents.existsForOperationSince(op.tenantId(), op.id(), op.updatedAt())) {
            log(op, "TRACK → estado=" + doc.estado() + " pero SIFEN avisó un cambio durante este paso: se consulta de nuevo ya");
            return new StepResult.Next(TRACK, Duration.ZERO, doc.cdc(), op.result());
        }
        if (!clock.now().isBefore(since.plus(TRACK_LIMIT))) {
            log(op, "TRACK → estado=" + doc.estado() + " tras " + TRACK_LIMIT.toHours() + " h: DEAD hasta que SIFEN avise el estado final");
            return new StepResult.Failed(ConnectorError.of(ErrorKind.EXPIRED, TRACKING_EXPIRED,
                    "SIFEN sigue en " + doc.estado() + " tras " + TRACK_LIMIT.toHours() + " h"));
        }
        int polls = ((Number) op.result().get("trackPolls")).intValue() + 1;
        Duration delay = TRACK_SCHEDULE.get(Math.min(polls, TRACK_SCHEDULE.size() - 1));
        Map<String, Object> result = new LinkedHashMap<>(doc.toResult());
        result.put("trackingSince", since.toString());
        result.put("trackPolls", polls);
        log(op, "TRACK → estado=" + doc.estado() + ": próxima consulta en " + delay);
        return new StepResult.Next(TRACK, delay, doc.cdc(), result);
    }

    /**
     * A step that already ran once (failed, interrupted by a dead worker, or replayed) may have reached
     * SIFEN, so it must verify before calling again.
     */
    static boolean isResend(Operation op) {
        return op.lastError() != null || op.attempts() > 1;
    }

    /** A verification that did not answer keeps the operation unverified, so the next attempt verifies again. */
    static ConnectorError stillUnverified(ConnectorError error) {
        if (error.kind() == ErrorKind.AUTH) {
            return error;
        }
        return new ConnectorError(ErrorKind.UNCERTAIN, error.code(), error.detail(), error.httpStatus(), null);
    }

    /** Fail-fast precondition: SIFEN rejects unsigned calls, so nothing is sent without both keys. */
    static Optional<ConnectorError> missingCredentials(StepContext context) {
        Map<String, String> values = context.target().credentials().values();
        if (values.containsKey("accessKey") && values.containsKey("secretKey")) {
            return Optional.empty();
        }
        return Optional.of(ConnectorError.of(ErrorKind.AUTH, "credentials-missing",
                "La conexión SIFEN requiere accessKey y secretKey"));
    }

    static Duration readTimeout(StepContext context) {
        return Duration.ofMillis(context.integration().policy().readTimeoutMs());
    }

    static void log(Operation op, String message) {
        log.log(INFO, "{0} op={1} tenant={2} step={3} attempt={4} {5}", op.flow().logPrefix(), op.id(),
                op.tenantId(), op.currentStep(), String.valueOf(op.attempts()), message);
    }
}
