package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Fiscal runtime API as the POS uses it (spec §8.1; contract of {@code nexus-fiscal.adapter.ts}). */
class InvoiceApiIT extends AbstractFiscalIT {

    @Test
    void should_persist_before_answering_and_never_duplicate_the_emission_when_the_same_request_repeats() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "APROBADO"));

        JsonNode accepted = body(issue(pos, invoice(ref), 202));
        Operation persisted = issueOperation(ref);
        assertThat(accepted.get("operationId").asString()).isEqualTo(persisted.id().toString());
        assertThat(accepted.get("status").asString()).isEqualTo("PENDING");

        JsonNode repeated = body(issue(pos, invoice(ref), 200));
        assertThat(repeated.get("operationId").asString()).isEqualTo(persisted.id().toString());
        assertThat(repeated.get("status").asString()).isEqualTo("PENDING");

        drain();
        JsonNode afterApproval = body(issue(pos, invoice(ref), 200));
        assertThat(afterApproval.get("status").asString()).isEqualTo("APPROVED");
        assertThat(count(submitsOf(ref))).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE tenant_id = :t AND idempotency_key = :k")
                .param("t", tenant).param("k", ref).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void should_answer_409_without_touching_sifen_when_the_source_ref_comes_back_with_another_payload() {
        String ref = newSourceRef();
        issue(pos, invoice(ref), 202);
        Map<String, Object> changed = invoice(ref);
        changed.put("globalDiscount", 100);

        JsonNode problem = body(issue(pos, changed, 409));

        assertThat(problem.get("code").asString()).isEqualTo("idempotency-conflict");
    }

    @Test
    void should_reject_with_422_and_persist_nothing_when_a_credit_note_has_no_reference() {
        String ref = newSourceRef();
        Map<String, Object> creditNote = invoice(ref);
        creditNote.put("documentType", "CREDIT_NOTE");

        JsonNode problem = body(issue(pos, creditNote, 422));

        assertThat(problem.get("code").asString()).isEqualTo("invalid-invoice");
        assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE idempotency_key = :k").param("k", ref)
                .query(Long.class).single()).isZero();
    }

    @Test
    void should_send_the_credit_term_to_sifen_when_the_invoice_is_a_credit_sale() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "APROBADO"));
        Map<String, Object> creditSale = creditSale(ref);
        creditSale.put("credit", Map.of("termDays", 30));

        issue(pos, creditSale, 202);
        drain();

        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("APPROVED");
        assertThat(count(submitsOf(ref)
                .withRequestBody(matchingJsonPath("$.header.condicionPago", equalTo("CREDITO")))
                .withRequestBody(matchingJsonPath("$.credito.condicion", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.credito.plazo", equalTo("30 dias"))))).isEqualTo(1);
    }

    @Test
    void should_reject_with_422_and_persist_nothing_when_a_credit_sale_has_no_credit_block() {
        String ref = newSourceRef();

        JsonNode problem = body(issue(pos, creditSale(ref), 422));

        assertThat(problem.get("code").asString()).isEqualTo("invalid-invoice");
        assertThat(problem.get("detail").asString()).contains("credit es obligatorio");
        assertThat(operationCount(ref)).isZero();
    }

    @Test
    void should_reject_with_422_and_persist_nothing_when_a_cash_sale_carries_a_credit_block() {
        String ref = newSourceRef();
        Map<String, Object> cashSale = invoice(ref);
        cashSale.put("credit", Map.of("installments", List.of(Map.of("amount", 4400, "dueDate", "2026-11-10"))));

        JsonNode problem = body(issue(pos, cashSale, 422));

        assertThat(problem.get("code").asString()).isEqualTo("invalid-invoice");
        assertThat(problem.get("detail").asString()).contains("credit solo se admite");
        assertThat(operationCount(ref)).isZero();
    }

    @Test
    void should_hide_documents_and_operations_of_another_tenant() {
        String ref = newSourceRef();
        Operation ownOperation = issue(ref);
        UUID otherTenant = createTenant(admin);
        String otherClient = clientToken(otherTenant, "fiscal:issue", "fiscal:read", "operations:read");

        perform(get("/api/v1/invoices/{ref}", ref).header("Authorization", bearer(otherClient)), 404);
        perform(get("/api/v1/invoices/{ref}/kude", ref).header("Authorization", bearer(otherClient)), 404);
        perform(post("/api/v1/invoices/{ref}/cancel", ref).header("Authorization", bearer(otherClient))
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", "x"))), 404);
        perform(get("/api/v1/operations/{id}", ownOperation.id()).header("Authorization", bearer(otherClient)), 404);
    }

    @Test
    void should_require_the_issue_scope_to_emit() {
        String readOnly = clientToken(tenant, "fiscal:read");

        issue(readOnly, invoice(newSourceRef()), 403);
    }

    @Test
    void should_cancel_asynchronously_and_report_cancelled_when_sifen_confirms() {
        String ref = approvedInvoice();
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar"))
                .willReturn(document(ref, "CANCELADO")));

        JsonNode accepted = body(cancel(ref, "Error en el cliente", 202));
        JsonNode repeated = body(cancel(ref, "Error en el cliente", 200));
        assertThat(repeated.get("operationId").asString()).isEqualTo(accepted.get("operationId").asString());
        drain();

        JsonNode operation = operation(accepted.get("operationId").asString());
        assertThat(operation.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(SIFEN.findAll(postRequestedFor(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar"))
                .withRequestBody(matchingJsonPath("$.motivo", equalTo("Error en el cliente"))))).hasSize(1);
    }

    @Test
    void should_expose_the_sifen_message_on_the_operation_when_sifen_refuses_the_cancellation() {
        String ref = approvedInvoice();
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar"))
                .willReturn(sifenError(409, "BUSINESS_RULE", "Plazo de cancelacion vencido")));

        JsonNode accepted = body(cancel(ref, "Fuera de plazo", 202));
        drain();

        JsonNode operation = operation(accepted.get("operationId").asString());
        assertThat(operation.get("status").asString()).isEqualTo("FAILED");
        assertThat(operation.get("lastError").get("message").asString()).isEqualTo("Plazo de cancelacion vencido");
        assertThat(operation.get("deadReason").isNull()).isTrue();
        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void should_refuse_to_cancel_a_document_that_was_not_issued() {
        String ref = newSourceRef();
        issue(ref);

        JsonNode problem = body(cancel(ref, "Anular", 409));

        assertThat(problem.get("code").asString()).isEqualTo("invoice-not-issued");
    }

    @Test
    void should_pass_the_kude_pdf_through_when_the_document_has_a_cdc() {
        String ref = approvedInvoice();
        byte[] pdf = "%PDF-1.4 kude".getBytes();
        SIFEN.stubFor(WireMock.get(urlEqualTo("/api/v1/kude/" + cdcFor(ref) + "?template=ticket"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/pdf").withBody(pdf)));

        MvcResult result = perform(get("/api/v1/invoices/{ref}/kude", ref).param("template", "ticket")
                .header("Authorization", bearer(pos)), 200);

        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(pdf);
    }

    private static Map<String, Object> creditSale(String ref) {
        Map<String, Object> request = invoice(ref);
        request.put("header", Map.of("currency", "PYG", "paymentCondition", "CREDIT", "paymentMethod", "EFECTIVO"));
        return request;
    }

    private long operationCount(String ref) {
        return jdbc.sql("SELECT count(*) FROM operations WHERE idempotency_key = :k").param("k", ref)
                .query(Long.class).single();
    }

    private String approvedInvoice() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "APROBADO"));
        Operation op = issue(ref);
        drain();
        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        return ref;
    }

    private MvcResult cancel(String ref, String reason, int expected) {
        return perform(post("/api/v1/invoices/{ref}/cancel", ref).header("Authorization", bearer(pos))
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", reason))), expected);
    }

    private JsonNode operation(String id) {
        return body(perform(get("/api/v1/operations/{id}", id).header("Authorization", bearer(pos)), 200));
    }
}
