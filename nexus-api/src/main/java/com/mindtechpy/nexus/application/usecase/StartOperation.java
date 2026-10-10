package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationMetrics;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.RetryLaterException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Persists a new operation or returns the existing one for the same idempotency key ({@code startOrFind}).
 * Same key and same payload hash: the existing operation, whatever its state. Same key and another hash:
 * 409 {@code idempotency-conflict}. A new operation additionally requires an integration that accepts
 * work, decryptable credentials and room in the tenant queue (else 429 with {@code Retry-After}). When it
 * returns, the operation is committed: callers may answer 202.
 */
public class StartOperation {

    static final Duration SATURATED_RETRY_AFTER = Duration.ofSeconds(30);

    public record Command(TenantId tenantId, UUID integrationId, OperationKind kind, String idempotencyKey,
                          Map<String, Object> payload, String correlationId) {
        @Override
        public String toString() {
            return "Command[tenant=" + tenantId + ", kind=" + kind + ", key=" + idempotencyKey + "]";
        }
    }

    /** {@code created} is false when an earlier request with the same key and payload already created it. */
    public record Started(Operation operation, boolean created) {
    }

    private final OperationRepository operations;
    private final IntegrationRepository integrations;
    private final ConnectionRepository connections;
    private final SecretCipherPort cipher;
    private final OperationMetrics metrics;
    private final ClockPort clock;
    private final TransactionRunner tx;
    private final long maxUnfinishedPerTenant;

    public StartOperation(OperationRepository operations, IntegrationRepository integrations,
                          ConnectionRepository connections, SecretCipherPort cipher, OperationMetrics metrics,
                          ClockPort clock, TransactionRunner tx, long maxUnfinishedPerTenant) {
        this.operations = operations;
        this.integrations = integrations;
        this.connections = connections;
        this.cipher = cipher;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = tx;
        this.maxUnfinishedPerTenant = maxUnfinishedPerTenant;
    }

    public Started execute(Command command) {
        Integration integration = integrations.findById(command.tenantId(), command.integrationId())
                .orElseThrow(() -> DomainException.notFound("Integración"));
        if (command.kind() == null || integration.flow() != command.kind().flow()) {
            throw DomainException.invalid("flow-mismatch", "El tipo de operación no corresponde al flujo de la integración");
        }
        Operation candidate = Operation.create(command.tenantId(), integration.id(), command.kind(),
                command.idempotencyKey(), command.payload(), integration.policy().maxAttempts(),
                command.correlationId(), clock.now());
        Started started = tx.inTransaction(() -> {
            var existing = operations.findByKey(command.tenantId(), command.kind(), command.idempotencyKey());
            if (existing.isPresent()) {
                return sameRequest(existing.get(), candidate);
            }
            integration.state().requireAcceptsNewOperations();
            if (operations.countUnfinished(command.tenantId()) >= maxUnfinishedPerTenant) {
                throw new RetryLaterException("queue-saturated",
                        "La cola del tenant está saturada; reintente más tarde", SATURATED_RETRY_AFTER);
            }
            requireCredentials(integration.tenantId(), integration.sourceConnectionId());
            requireCredentials(integration.tenantId(), integration.targetConnectionId());
            if (!operations.insertIfAbsent(candidate)) {
                return sameRequest(operations.findByKey(command.tenantId(), command.kind(), command.idempotencyKey())
                        .orElseThrow(), candidate);
            }
            return new Started(candidate, true);
        });
        if (started.created()) {
            metrics.operationCreated(command.kind());
        }
        return started;
    }

    private static Started sameRequest(Operation existing, Operation candidate) {
        if (!existing.requestHash().equals(candidate.requestHash())) {
            throw DomainException.conflict("idempotency-conflict",
                    "La clave idempotente ya se usó con un contenido distinto");
        }
        return new Started(existing, false);
    }

    private void requireCredentials(TenantId tenantId, UUID connectionId) {
        if (connectionId == null) {
            return;
        }
        Connection connection = connections.findById(tenantId, connectionId)
                .orElseThrow(() -> DomainException.conflict("credentials-missing", "La conexión de la integración no existe"));
        try {
            cipher.open(connection.sealedCredentials(), connection.credentialsAad());
        } catch (DomainException e) {
            throw DomainException.conflict("credentials-missing", "Las credenciales de la conexión " + connection.name()
                    + " no se pueden leer: vuelva a cargarlas");
        }
    }
}
