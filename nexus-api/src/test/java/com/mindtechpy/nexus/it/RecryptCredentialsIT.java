package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.usecase.RecryptCredentials;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.infrastructure.crypto.AesGcmSecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code [NEXUS-RECRYPT]} master-key rotation against the real {@code connections} table. */
class RecryptCredentialsIT extends AbstractPostgresIT {

    private static final ConnectionCredentials CREDENTIALS = new ConnectionCredentials(Map.of("apiKey", "gh_live_rotar"));

    @Autowired
    private ConnectionRepository connections;
    @Autowired
    private AuditRepository audit;
    @Autowired
    private ClockPort clock;

    private final String oldKey = randomKey();
    private final String newKey = randomKey();

    @Test
    void should_reseal_with_the_new_key_every_credential_sealed_with_the_previous_one_and_audit_it() {
        TenantId tenant = new TenantId(createTenant(platformAdminToken()));
        UUID onOldKey = insert(tenant, new AesGcmSecretCipher(oldKey, null, json));
        UUID onNewKey = insert(tenant, new AesGcmSecretCipher(newKey, null, json));
        byte[] newKeyBytes = sealed(tenant, onNewKey);

        RecryptCredentials.Result first = recrypt();
        RecryptCredentials.Result second = recrypt();

        AesGcmSecretCipher newKeyOnly = new AesGcmSecretCipher(newKey, null, json);
        assertThat(newKeyOnly.open(sealed(tenant, onOldKey), Connection.credentialsAad(tenant, onOldKey))).isEqualTo(CREDENTIALS);
        assertThat(sealed(tenant, onNewKey)).isEqualTo(newKeyBytes);
        assertThat(first.recrypted()).isGreaterThanOrEqualTo(1);
        assertThat(second.recrypted()).isZero();
        assertThat(auditActions(tenant, onOldKey)).containsExactly("connection.credentials-recrypted");
        assertThat(auditActions(tenant, onNewKey)).isEmpty();
    }

    @Test
    void should_leave_untouched_and_report_a_credential_no_configured_key_opens() {
        TenantId tenant = new TenantId(createTenant(platformAdminToken()));
        UUID foreign = insert(tenant, new AesGcmSecretCipher(randomKey(), null, json));
        byte[] before = sealed(tenant, foreign);

        RecryptCredentials.Result result = recrypt();

        assertThat(sealed(tenant, foreign)).isEqualTo(before);
        assertThat(result.unreadable()).isGreaterThanOrEqualTo(1);
        assertThat(auditActions(tenant, foreign)).containsExactly("connection.recrypt-failed");
    }

    private RecryptCredentials.Result recrypt() {
        return new RecryptCredentials(connections, new AesGcmSecretCipher(newKey, oldKey, json), audit, clock).execute();
    }

    private UUID insert(TenantId tenant, AesGcmSecretCipher cipher) {
        UUID id = UUID.randomUUID();
        connections.insert(new Connection(id, tenant, ConnectorType.GIFTHUB, "gifthub-" + id,
                "http://api.comercio-central.svc.cluster.local:3000", cipher.seal(CREDENTIALS, Connection.credentialsAad(tenant, id)),
                Map.of(), null, null));
        return id;
    }

    private byte[] sealed(TenantId tenant, UUID id) {
        return connections.findById(tenant, id).orElseThrow().sealedCredentials();
    }

    private List<String> auditActions(TenantId tenant, UUID connectionId) {
        return jdbc.sql("""
                        SELECT action FROM audit_events WHERE tenant_id = :t AND resource_id = :id
                          AND action IN ('connection.credentials-recrypted', 'connection.recrypt-failed')""")
                .param("t", tenant.value()).param("id", connectionId.toString()).query(String.class).list();
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
