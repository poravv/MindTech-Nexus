package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.InboundEventRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.shared.DomainException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static java.lang.System.Logger.Level.INFO;

/**
 * SIFEN state-change webhook ({@code APROBADO|RECHAZADO|CANCELADO|PENDIENTE_ENVIO}). The event is a hint,
 * not the truth: it makes the waiting {@code ISSUE_INVOICE} run now, and its next step reads the state
 * from SIFEN ({@code GET /facturas/{transactionId}}), as SIFEN recommends because delivery order is not
 * guaranteed. Deliveries are deduplicated by {@code eventId}.
 */
public class ReceiveSifenEvent {

    public record Event(String eventId, String evento, String transactionId) {
    }

    public enum Outcome { ACCEPTED, DUPLICATE }

    private static final System.Logger log = System.getLogger(ReceiveSifenEvent.class.getName());

    private final ConnectionRepository connections;
    private final SecretCipherPort cipher;
    private final InboundEventRepository inboundEvents;
    private final OperationRepository operations;
    private final ClockPort clock;

    public ReceiveSifenEvent(ConnectionRepository connections, SecretCipherPort cipher,
                             InboundEventRepository inboundEvents, OperationRepository operations, ClockPort clock) {
        this.connections = connections;
        this.cipher = cipher;
        this.inboundEvents = inboundEvents;
        this.operations = operations;
        this.clock = clock;
    }

    /**
     * @param isSignedWith checks the delivery signature with a candidate secret (HMAC lives in the web adapter)
     * @param event        parsed body, or null when it is not valid JSON; only read after the signature passed
     * @throws DomainException 404 unknown SIFEN connection, 401 bad signature or no {@code webhookSecret},
     *                         422 event without {@code eventId}/{@code evento}
     */
    public Outcome execute(UUID connectionId, Predicate<String> isSignedWith, Event event) {
        Connection connection = connections.findForWebhook(connectionId)
                .filter(found -> found.connector() == ConnectorType.SIFEN)
                .orElseThrow(() -> DomainException.notFound("Conexión"));
        String secret = cipher.open(connection.sealedCredentials(), connection.credentialsAad()).values().get("webhookSecret");
        if (secret == null || !isSignedWith.test(secret)) {
            throw DomainException.unauthorized("invalid-signature", "Firma del webhook inválida o vencida");
        }
        if (event == null || isBlank(event.eventId()) || isBlank(event.evento())) {
            throw DomainException.invalid("invalid-event", "El evento requiere eventId y evento");
        }
        Instant now = clock.now();
        Optional<Operation> issue = isBlank(event.transactionId()) ? Optional.empty()
                : operations.findByKey(connection.tenantId(), OperationKind.ISSUE_INVOICE, event.transactionId());
        boolean isNew = inboundEvents.insertIfAbsent(new InboundEventRepository.InboundEvent(UUID.randomUUID(),
                connection.tenantId(), connection.id(), "SIFEN", event.eventId(), event.evento(),
                issue.map(Operation::id).orElse(null), now));
        if (!isNew) {
            return Outcome.DUPLICATE;
        }
        boolean isWaiting = issue.filter(op -> op.status() == OperationStatus.PENDING || op.status() == OperationStatus.RETRY)
                .map(op -> operations.update(op, op.dueAt(now)))
                .orElse(false);
        log.log(INFO, "[NEXUS-FISCAL] webhook SIFEN conexión={0} tenant={1} evento={2} op={3} {4}", connection.id(),
                connection.tenantId(), event.evento(), issue.map(op -> op.id().toString()).orElse("-"),
                isWaiting ? "→ la operación se consulta ahora" : "→ sin operación en espera");
        return Outcome.ACCEPTED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
