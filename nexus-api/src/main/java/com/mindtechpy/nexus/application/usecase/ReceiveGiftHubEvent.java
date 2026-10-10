package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.InboundEventRepository;
import com.mindtechpy.nexus.application.port.out.InboundEventRepository.InboundEvent;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.RateLimiterPort;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.integration.IntegrationPolicy;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.order.ChannelOrder;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.RetryLaterException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static java.lang.System.Logger.Level.INFO;

/**
 * {@code [NEXUS-ORDER]} entry point of GiftHub webhooks ({@code POST /api/v1/webhooks/gifthub/{connectionId}}).
 * The connection in the URL gives the tenant; its {@code webhookSecret} credential verifies the HMAC
 * (±300 s, any of the rotation signatures). Each {@code X-GiftHub-Delivery} is processed once: the delivery
 * is recorded in the same transaction that starts the operation, so a failure leaves nothing recorded and
 * GiftHub's retry processes it again; a redelivery finds the operation by its idempotency key (nothing new
 * is created) and answers DUPLICATE. {@code order.created} starts {@code CREATE_ORDER} (key = order id);
 * {@code order.cancelled} of an order whose POS sale exists starts {@code ORDER_STATUS POS_CANCELLED};
 * {@code ping} and every other event are acknowledged without work. When the ORDER integration cannot take
 * work it answers 429 with {@code Retry-After}, so GiftHub retries instead of dropping the order.
 */
public class ReceiveGiftHubEvent {

    static final Duration RETRY_AFTER = Duration.ofSeconds(60);
    private static final IntegrationPolicy PER_CONNECTION = new IntegrationPolicy(10.0, 50, 10, null, null, null, null, null);
    private static final System.Logger log = System.getLogger(ReceiveGiftHubEvent.class.getName());

    /**
     * @param signedWith true when the request carries a valid GiftHub signature (and fresh timestamp) for the
     *                   given webhook secret; computed over the raw body by the web adapter
     * @param event      the parsed body, empty when it is not JSON
     */
    public record Delivery(UUID connectionId, String deliveryId, Predicate<String> signedWith, Map<String, Object> event) {
    }

    /** {@code ACCEPTED}: an operation exists for the event (new, or the one an earlier delivery started). */
    public enum Outcome { ACCEPTED, DUPLICATE, IGNORED }

    public record Received(Outcome outcome, UUID operationId) {
    }

    private final ConnectionRepository connections;
    private final IntegrationRepository integrations;
    private final OperationRepository operations;
    private final InboundEventRepository inboundEvents;
    private final StartOperation startOperation;
    private final SecretCipherPort cipher;
    private final RateLimiterPort rateLimiter;
    private final ClockPort clock;
    private final TransactionRunner tx;

    public ReceiveGiftHubEvent(ConnectionRepository connections, IntegrationRepository integrations,
                               OperationRepository operations, InboundEventRepository inboundEvents,
                               StartOperation startOperation, SecretCipherPort cipher, RateLimiterPort rateLimiter,
                               ClockPort clock, TransactionRunner tx) {
        this.connections = connections;
        this.integrations = integrations;
        this.operations = operations;
        this.inboundEvents = inboundEvents;
        this.startOperation = startOperation;
        this.cipher = cipher;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * The per-connection rate limit applies only after the signature is verified, so unsigned traffic
     * cannot use up the quota of the real GiftHub deliveries.
     *
     * @throws DomainException 404 unknown connection, 401 bad signature or stale timestamp, 422 malformed
     *                         event, 429 ({@link RetryLaterException}) when the connection exceeds its rate
     *                         limit or the integration does not take work
     */
    public Received execute(Delivery delivery) {
        Connection connection = connections.findForWebhook(delivery.connectionId())
                .filter(found -> found.connector() == ConnectorType.GIFTHUB)
                .orElseThrow(() -> DomainException.notFound("Conexión"));
        String secret = cipher.open(connection.sealedCredentials(), connection.credentialsAad()).values().get("webhookSecret");
        if (secret == null || !delivery.signedWith().test(secret)) {
            throw DomainException.unauthorized("invalid-signature", "Firma o timestamp del webhook inválidos");
        }
        if (!rateLimiter.tryAcquire(connection.id(), PER_CONNECTION)) {
            throw new RetryLaterException("rate-limited", "Demasiados webhooks para esta conexión", Duration.ofSeconds(1));
        }
        try {
            return process(connection, delivery);
        } finally {
            rateLimiter.release(connection.id());
        }
    }

    private Received process(Connection connection, Delivery delivery) {
        if (!(delivery.event().get("type") instanceof String type)) {
            throw DomainException.invalid("invalid-event", "El cuerpo no es un evento de GiftHub");
        }
        if (delivery.deliveryId() == null || !delivery.deliveryId().matches("^[\\x21-\\x7E]{1,200}$")) {
            throw DomainException.invalid("invalid-delivery", "Falta X-GiftHub-Delivery");
        }
        if ("ping".equals(type)) {
            return new Received(Outcome.IGNORED, null);
        }
        Received received = tx.inTransaction(() -> {
            Operation started = switch (type) {
                case "order.created" -> startCreateOrder(connection, delivery);
                case "order.cancelled" -> startPosCancellation(connection, delivery);
                default -> null;
            };
            UUID operationId = started == null ? null : started.id();
            if (!inboundEvents.insertIfAbsent(new InboundEvent(UUID.randomUUID(), connection.tenantId(), connection.id(),
                    "GIFTHUB", delivery.deliveryId(), type, operationId, clock.now()))) {
                return new Received(Outcome.DUPLICATE, null);
            }
            return new Received(started == null ? Outcome.IGNORED : Outcome.ACCEPTED, operationId);
        });
        log.log(INFO, "[NEXUS-ORDER] webhook tenant={0} delivery={1} type={2} -> {3} op={4}", connection.tenantId(),
                delivery.deliveryId(), type, received.outcome(), received.operationId());
        return received;
    }

    private Operation startCreateOrder(Connection connection, Delivery delivery) {
        Map<String, Object> payload = ChannelOrder.payloadOf(order(delivery));
        Integration integration = orderIntegration(connection);
        return start(integration, OperationKind.CREATE_ORDER, (String) payload.get("id"), payload, delivery);
    }

    /** Only once the POS sale exists; before that, CREATE_ORDER itself notices the cancellation at NOTIFY. */
    private Operation startPosCancellation(Connection connection, Delivery delivery) {
        String orderId = String.valueOf(order(delivery).get("id"));
        Operation created = operations.findByKey(connection.tenantId(), OperationKind.CREATE_ORDER, orderId).orElse(null);
        if (created == null || created.result() == null || created.result().get("saleId") == null) {
            return null;
        }
        Integration integration = integrations.findById(connection.tenantId(), created.integrationId()).orElseThrow();
        return start(integration, OperationKind.ORDER_STATUS, orderId + ":POS_CANCELLED",
                Map.of("target", "POS_CANCELLED", "orderId", orderId), delivery);
    }

    private Operation start(Integration integration, OperationKind kind, String key, Map<String, Object> payload,
                            Delivery delivery) {
        try {
            return startOperation.execute(new StartOperation.Command(integration.tenantId(), integration.id(), kind, key,
                    payload, delivery.deliveryId())).operation();
        } catch (DomainException e) {
            if (e.kind() != DomainException.Kind.CONFLICT || "idempotency-conflict".equals(e.code())) {
                throw e;
            }
            throw new RetryLaterException(e.code(), e.getMessage(), RETRY_AFTER);
        }
    }

    private Integration orderIntegration(Connection giftHub) {
        TenantId tenantId = giftHub.tenantId();
        return integrations.findAll(tenantId).stream()
                .filter(integration -> integration.flow() == Flow.ORDER && giftHub.id().equals(integration.sourceConnectionId()))
                .findFirst()
                .orElseThrow(() -> new RetryLaterException("integration-missing",
                        "No hay una integración ORDER con esta conexión GiftHub como origen", RETRY_AFTER));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> order(Delivery delivery) {
        if (delivery.event().get("data") instanceof Map<?, ?> data && data.get("order") instanceof Map<?, ?> order) {
            return (Map<String, Object>) order;
        }
        throw DomainException.invalid("invalid-order", "El evento no trae data.order");
    }
}
