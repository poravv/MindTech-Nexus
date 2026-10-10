package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.SecretCipherPort;
import com.mindtechpy.nexus.application.port.out.SifenPort;
import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.fiscal.InvoiceState;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.RetryLaterException;
import com.mindtechpy.nexus.domain.shared.TenantId;

import java.time.Duration;
import java.util.Set;

/**
 * Read side of fiscal documents for machine clients; the tenant always comes from the token, so a
 * document of another tenant is not found (ADR-005).
 */
public class InvoiceQueries {

    private static final Set<String> KUDE_TEMPLATES = Set.of("ticket", "full");
    private static final Duration KUDE_RETRY_AFTER = Duration.ofSeconds(10);

    private final OperationRepository operations;
    private final IntegrationRepository integrations;
    private final ConnectionRepository connections;
    private final SecretCipherPort cipher;
    private final SifenPort sifen;

    public InvoiceQueries(OperationRepository operations, IntegrationRepository integrations,
                          ConnectionRepository connections, SecretCipherPort cipher, SifenPort sifen) {
        this.operations = operations;
        this.integrations = integrations;
        this.connections = connections;
        this.cipher = cipher;
        this.sifen = sifen;
    }

    public InvoiceState get(TenantId tenantId, String sourceRef) {
        Operation issue = operations.findByKey(tenantId, OperationKind.ISSUE_INVOICE, sourceRef)
                .orElseThrow(() -> DomainException.notFound("Factura"));
        Operation cancel = operations.findByKey(tenantId, OperationKind.CANCEL_INVOICE, sourceRef).orElse(null);
        return InvoiceState.of(issue, cancel);
    }

    /**
     * KuDE PDF straight from SIFEN, never stored. 409 {@code invoice-without-cdc} until SIFEN assigned a CDC;
     * a SIFEN outage is 429 with {@code Retry-After} (nothing to fix on the client side but waiting).
     *
     * @param template {@code ticket} or {@code full}; null lets SIFEN choose
     */
    public byte[] kude(TenantId tenantId, String sourceRef, String template) {
        if (template != null && !KUDE_TEMPLATES.contains(template)) {
            throw DomainException.invalid("invalid-template", "template debe ser ticket o full");
        }
        InvoiceState invoice = get(tenantId, sourceRef);
        if (invoice.cdc() == null) {
            throw DomainException.conflict("invoice-without-cdc", "El documento todavía no tiene CDC");
        }
        Integration fiscal = IssueInvoice.fiscalIntegration(integrations, tenantId);
        Connection connection = connections.findById(tenantId, fiscal.targetConnectionId())
                .orElseThrow(() -> DomainException.conflict("credentials-missing", "La conexión SIFEN no existe"));
        Endpoint endpoint = new Endpoint(connection, cipher.open(connection.sealedCredentials(), connection.credentialsAad()));
        return switch (sifen.kude(endpoint, invoice.cdc(), template)) {
            case SifenPort.Ok<byte[]>(byte[] pdf) -> pdf;
            case SifenPort.NotFound<byte[]>() -> throw DomainException.notFound("KuDE");
            case SifenPort.Failed<byte[]>(var error) when error.kind() == ErrorKind.REJECTED ->
                    throw DomainException.conflict("kude-unavailable", "SIFEN no genera el KuDE: " + error.detail());
            case SifenPort.Failed<byte[]>(var error) -> throw new RetryLaterException("sifen-unavailable",
                    "SIFEN no respondió el KuDE (" + error.kind() + "); reintente", KUDE_RETRY_AFTER);
        };
    }
}
