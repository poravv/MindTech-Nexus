package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
    void should_split_the_line_in_two_for_sifen_when_the_discount_does_not_divide_by_the_quantity() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "APROBADO"));
        Map<String, Object> request = invoice(ref);
        request.put("items", List.of(Map.of("code", "PAN-001", "description", "Chipa", "quantity", "3",
                "unitPrice", 10000, "discount", 1000, "vatRate", "10")));

        issue(pos, request, 202);
        drain();

        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("APPROVED");
        assertThat(count(submitsOf(ref)
                .withRequestBody(matchingJsonPath("$.detail.length()", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.detail[0].cantidad", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.detail[0].descuento", equalTo("333")))
                .withRequestBody(matchingJsonPath("$.detail[1].cantidad", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.detail[1].descuento", equalTo("334"))))).isEqualTo(1);
    }

    @Test
    void should_reject_with_422_and_persist_nothing_when_a_fractional_quantity_discount_does_not_split_per_unit() {
        String ref = newSourceRef();
        Map<String, Object> request = invoice(ref);
        request.put("items", List.of(Map.of("code", "CAR-001", "description", "Carne", "quantity", "2.5",
                "unitPrice", 10000, "discount", 1001, "vatRate", "10", "unit", "KG")));

        JsonNode problem = body(issue(pos, request, 422));

        assertThat(problem.get("code").asString()).isEqualTo("discount-not-divisible");
        assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE idempotency_key = :ref").param("ref", ref)
                .query(Long.class).single()).isZero();
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
    void should_fail_with_cancel_window_expired_and_the_sifen_message_when_sifen_refuses_a_late_cancellation() {
        String ref = approvedInvoice();
        String message = "Plazo de cancelacion excedido. Limite: 48h, transcurrido: 50h desde aprobacion";
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar"))
                .willReturn(sifenError(409, "BUSINESS_RULE", message)));

        JsonNode accepted = body(cancel(ref, "Fuera de plazo", 202));
        drain();

        JsonNode operation = operation(accepted.get("operationId").asString());
        assertThat(operation.get("status").asString()).isEqualTo("FAILED");
        assertThat(operation.get("lastError").get("code").asString()).isEqualTo("cancel-window-expired");
        assertThat(operation.get("lastError").get("message").asString()).isEqualTo(message);
        assertThat(operation.get("deadReason").isNull()).isTrue();
        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void should_put_a_failed_cancellation_back_in_the_queue_and_reach_sifen_when_the_pos_repeats_it() {
        String ref = approvedInvoice();
        stubFind(ref, document(ref, "APROBADO"));
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar")).inScenario(ref)
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("fixed")
                .willReturn(sifenError(409, "BUSINESS_RULE", "SIFEN no registró el evento")));
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar")).inScenario(ref)
                .whenScenarioStateIs("fixed").willReturn(document(ref, "CANCELADO")));
        String operationId = body(cancel(ref, "Error en el cliente", 202)).get("operationId").asString();
        drain();
        assertThat(operation(operationId).get("status").asString()).isEqualTo("FAILED");

        JsonNode retried = body(cancel(ref, "Error en el cliente", 202));
        drain();

        assertThat(retried.get("operationId").asString()).isEqualTo(operationId);
        assertThat(operation(operationId).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(count(postRequestedFor(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar")))).isEqualTo(2);
        assertThat(count(findsOf(ref))).isEqualTo(1);
    }

    @Test
    void should_return_the_existing_cancellation_without_calling_sifen_again_when_it_is_in_flight_or_succeeded() {
        String ref = approvedInvoice();
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar"))
                .willReturn(document(ref, "CANCELADO")));
        String operationId = body(cancel(ref, "Anulación", 202)).get("operationId").asString();

        JsonNode inFlight = body(cancel(ref, "Anulación", 200));
        drain();
        JsonNode afterSuccess = body(cancel(ref, "Anulación", 200));

        assertThat(inFlight.get("operationId").asString()).isEqualTo(operationId);
        assertThat(inFlight.get("status").asString()).isEqualTo("PENDING");
        assertThat(afterSuccess.get("operationId").asString()).isEqualTo(operationId);
        assertThat(afterSuccess.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(count(postRequestedFor(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar")))).isEqualTo(1);
    }

    @Test
    void should_leave_a_dead_cancellation_for_a_human_replay_when_the_pos_repeats_it() {
        String ref = approvedInvoice();
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar"))
                .willReturn(sifenError(401, "UNAUTHORIZED", "Firma inválida")));
        String operationId = body(cancel(ref, "Anulación", 202)).get("operationId").asString();
        drain();

        JsonNode repeated = body(cancel(ref, "Anulación", 200));
        drain();

        assertThat(repeated.get("operationId").asString()).isEqualTo(operationId);
        assertThat(repeated.get("status").asString()).isEqualTo("DEAD");
        assertThat(count(postRequestedFor(urlEqualTo("/api/v1/facturas/" + ref + "/cancelar")))).isEqualTo(1);
    }

    @Test
    void should_verify_and_send_again_the_same_transaction_when_a_failed_emission_is_repeated() {
        String ref = newSourceRef();
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas")).inScenario(ref)
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(ref)))
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("fixed")
                .willReturn(sifenError(422, "VALIDATION_ERROR", "Timbrado vencido")));
        SIFEN.stubFor(WireMock.post(urlEqualTo("/api/v1/facturas")).inScenario(ref)
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(ref)))
                .whenScenarioStateIs("fixed").willReturn(document(ref, "APROBADO")));
        stubFind(ref, sifenError(404, "NOT_FOUND", "No existe"));
        Operation failed = issue(ref);
        drain();
        assertThat(reload(failed.id()).status()).isEqualTo(OperationStatus.FAILED);

        JsonNode retried = body(issue(pos, invoice(ref), 202));
        drain();

        assertThat(retried.get("operationId").asString()).isEqualTo(failed.id().toString());
        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("APPROVED");
        assertThat(count(findsOf(ref))).isEqualTo(1);
        assertThat(count(submitsOf(ref))).isEqualTo(2);
    }

    @Test
    void should_not_retry_an_emission_that_sifen_processed_and_rejected() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "RECHAZADO"));
        Operation rejected = issue(ref);
        drain();

        JsonNode repeated = body(issue(pos, invoice(ref), 200));
        drain();

        assertThat(repeated.get("status").asString()).isEqualTo("REJECTED");
        assertThat(reload(rejected.id()).status()).isEqualTo(OperationStatus.FAILED);
        assertThat(count(submitsOf(ref))).isEqualTo(1);
        assertThat(count(findsOf(ref))).isZero();
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
