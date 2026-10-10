package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.domain.audit.AuditEvent;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.shared.DomainException;

import java.util.Map;
import java.util.Optional;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * {@code [NEXUS-RECRYPT]} master-key rotation (ADR-006): seals again with the current key every connection
 * credential still sealed with {@code NEXUS_MASTER_KEY_PREVIOUS}. Idempotent (rows already on the current key are
 * skipped) and lossless: a row no configured key opens, or one edited meanwhile, is left untouched and reported.
 * Each re-sealed or unreadable row is audited in its tenant.
 */
public class RecryptCredentials {

    /** {@code unreadable} &gt; 0 means the previous key must stay configured until those rows are fixed. */
    public record Result(int recrypted, int alreadyCurrent, int unreadable, int changedMeanwhile) {
    }

    private static final System.Logger log = System.getLogger(RecryptCredentials.class.getName());

    private final ConnectionRepository connections;
    private final SecretCipherPort cipher;
    private final AuditRepository audit;
    private final ClockPort clock;

    public RecryptCredentials(ConnectionRepository connections, SecretCipherPort cipher, AuditRepository audit, ClockPort clock) {
        this.connections = connections;
        this.cipher = cipher;
        this.audit = audit;
        this.clock = clock;
    }

    public Result execute() {
        int recrypted = 0;
        int alreadyCurrent = 0;
        int unreadable = 0;
        int changedMeanwhile = 0;
        for (Connection connection : connections.findAllTenants()) {
            Optional<byte[]> resealed;
            try {
                resealed = cipher.resealWithCurrentKey(connection.sealedCredentials(), connection.credentialsAad());
            } catch (DomainException e) {
                unreadable++;
                log.log(WARNING, "[NEXUS-RECRYPT] tenant={0} conexión={1}: ninguna clave la descifra, queda sin tocar",
                        connection.tenantId(), connection.id());
                audit(connection, "connection.recrypt-failed");
                continue;
            }
            if (resealed.isEmpty()) {
                alreadyCurrent++;
            } else if (connections.replaceCredentials(connection.tenantId(), connection.id(), connection.sealedCredentials(),
                    resealed.get())) {
                recrypted++;
                audit(connection, "connection.credentials-recrypted");
            } else {
                changedMeanwhile++;
            }
        }
        Result result = new Result(recrypted, alreadyCurrent, unreadable, changedMeanwhile);
        log.log(unreadable > 0 ? WARNING : INFO, "[NEXUS-RECRYPT] fin: {0}", result);
        return result;
    }

    private void audit(Connection connection, String action) {
        audit.append(AuditEvent.of(Actor.system(), connection.tenantId(), action, "connection", connection.id().toString(),
                null, Map.of("connector", connection.connector().name()), clock.now()));
    }
}
