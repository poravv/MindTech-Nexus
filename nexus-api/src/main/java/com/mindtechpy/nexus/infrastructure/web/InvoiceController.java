package com.mindtechpy.nexus.infrastructure.web;

import com.mindtechpy.nexus.application.usecase.CancelInvoice;
import com.mindtechpy.nexus.application.usecase.InvoiceQueries;
import com.mindtechpy.nexus.application.usecase.IssueInvoice;
import com.mindtechpy.nexus.application.usecase.StartOperation;
import com.mindtechpy.nexus.domain.fiscal.InvoiceRequest;
import com.mindtechpy.nexus.domain.fiscal.InvoiceState;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Fiscal runtime API (spec §8.1) used by the POS; the tenant comes from the token. Issue and cancel answer
 * 202 once the operation is persisted, or 200 with the current state when the same request was already
 * received.
 */
@Tag(name = "Runtime · Facturación electrónica")
@RestController
@RequestMapping("/api/v1/invoices")
class InvoiceController {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    record Accepted(UUID operationId, String sourceRef, String status) {
        static Accepted of(Operation operation) {
            return new Accepted(operation.id(), operation.idempotencyKey(), operation.status().name());
        }
    }

    record InvoiceView(String sourceRef, UUID operationId, String status, String cdc, String documentNumber,
                       String providerStatus, String providerCode, String providerMessage, Long total, Instant updatedAt,
                       ConnectorErrorView error) {
        static InvoiceView of(InvoiceState state) {
            return new InvoiceView(state.sourceRef(), state.operationId(), state.status().name(), state.cdc(),
                    state.documentNumber(), state.providerStatus(), state.providerCode(), state.providerMessage(),
                    state.total(), state.updatedAt(), ConnectorErrorView.of(state.error()));
        }
    }

    record CancelRequest(String reason) {
    }

    private final IssueInvoice issueInvoice;
    private final CancelInvoice cancelInvoice;
    private final InvoiceQueries invoiceQueries;
    private final JsonMapper json;

    InvoiceController(IssueInvoice issueInvoice, CancelInvoice cancelInvoice, InvoiceQueries invoiceQueries,
                      JsonMapper json) {
        this.issueInvoice = issueInvoice;
        this.cancelInvoice = cancelInvoice;
        this.invoiceQueries = invoiceQueries;
        this.json = json;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Emitir factura, nota de crédito o débito (idempotente por sourceRef)",
            description = "items[].discount es el descuento total de la línea (no por unidad); Nexus lo envía a SIFEN por "
                    + "unidad; con cantidad entera, si no se reparte en guaraníes enteros, la línea se divide en dos "
                    + "(mismo código y precio) y el total queda exacto. 422 discount-not-divisible solo con cantidad "
                    + "fraccionaria que no entra en la tolerancia de SIFEN (0,8).")
    @PostMapping
    ResponseEntity<Object> issue(@RequestBody InvoiceRequest request) {
        Actor client = TenantContext.currentClient(Scope.FISCAL_ISSUE);
        StartOperation.Started started = issueInvoice.execute(client.tenantId(), request,
                json.convertValue(request, MAP), MDC.get(CorrelationIdFilter.MDC_KEY));
        if (started.created()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Accepted.of(started.operation()));
        }
        return ResponseEntity.ok(InvoiceView.of(invoiceQueries.get(client.tenantId(), request.sourceRef())));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Estado del documento (404 si no existe en el tenant del token)")
    @GetMapping("/{sourceRef}")
    InvoiceView get(@PathVariable String sourceRef) {
        Actor client = TenantContext.currentClient(Scope.FISCAL_READ);
        return InvoiceView.of(invoiceQueries.get(client.tenantId(), sourceRef));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Cancelar un documento emitido (asíncrono: seguir operationId)")
    @PostMapping("/{sourceRef}/cancel")
    ResponseEntity<Accepted> cancel(@PathVariable String sourceRef, @RequestBody CancelRequest request) {
        Actor client = TenantContext.currentClient(Scope.FISCAL_ISSUE);
        StartOperation.Started started = cancelInvoice.execute(client.tenantId(), sourceRef, request.reason(),
                MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(started.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(Accepted.of(started.operation()));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "KuDE en PDF desde SIFEN (no se almacena)")
    @GetMapping("/{sourceRef}/kude")
    ResponseEntity<byte[]> kude(@PathVariable String sourceRef, @RequestParam(required = false) String template) {
        Actor client = TenantContext.currentClient(Scope.FISCAL_READ);
        byte[] pdf = invoiceQueries.kude(client.tenantId(), sourceRef, template);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("KuDE.pdf").build().toString())
                .body(pdf);
    }
}
