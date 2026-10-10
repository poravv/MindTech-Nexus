package com.mindtechpy.nexus.it;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.infrastructure.connectors.sifen.SifenHmacSigner;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Saga {@code ISSUE_INVOICE} by branch (spec §9, §14) against WireMock SIFEN. */
class IssueInvoiceSagaIT extends AbstractFiscalIT {

    @Autowired
    private MeterRegistry meters;

    @Test
    void should_succeed_with_signed_request_and_cdc_when_sifen_approves_on_submit() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "APROBADO"));

        Operation op = issue(ref);
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        JsonNode state = invoiceState(ref);
        assertThat(state.get("status").asString()).isEqualTo("APPROVED");
        assertThat(state.get("cdc").asString()).isEqualTo(cdcFor(ref));
        assertThat(state.get("documentNumber").asString()).isEqualTo("001-001-0000123");
        assertThat(state.get("total").asLong()).isEqualTo(4400);

        LoggedRequest sent = SIFEN.findAll(submitsOf(ref)).getFirst();
        assertThat(sent.getHeader("X-Access-Key")).isEqualTo(ACCESS_KEY);
        assertThat(sent.getHeader("X-Signature")).isEqualTo(SifenHmacSigner.sign(SECRET_KEY, "POST", "/api/v1/facturas",
                sent.getHeader("X-Timestamp"), sent.getHeader("X-Nonce"), sent.getBody()));
        JsonNode body = json.readTree(sent.getBodyAsString());
        assertThat(body.has("emisor")).isFalse();
        assertThat(body.get("sucursalId").asString()).isEqualTo("SUC-001");
        assertThat(body.get("puntoExpedicionId").asString()).isEqualTo("CAJA-01");
        assertThat(meters.find("nexus.connector.latency").tags("connector", "SIFEN", "operation", "submit", "outcome", "ok")
                .timer()).isNotNull();
    }

    @Test
    void should_track_with_growing_delays_until_final_when_sifen_answers_pending() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "PENDIENTE_ENVIO"));
        Operation op = issue(ref);

        drain();
        Operation tracking = reload(op.id());
        assertThat(tracking.status()).isEqualTo(OperationStatus.PENDING);
        assertThat(tracking.currentStep()).isEqualTo("TRACK");
        assertThat(Duration.between(Instant.now(), nextRunAt(op.id())).toSeconds()).isCloseTo(60, within(5L));
        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("SUBMITTED");

        stubFind(ref, document(ref, "EN_PROCESO"));
        makeDue(op.id());
        drain();
        assertThat(reload(op.id()).currentStep()).isEqualTo("TRACK");
        assertThat(Duration.between(Instant.now(), nextRunAt(op.id())).toSeconds()).isCloseTo(300, within(5L));

        stubFind(ref, document(ref, "APROBADO"));
        makeDue(op.id());
        drain();
        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("APPROVED");
        assertThat(count(submitsOf(ref))).isEqualTo(1);
    }

    @Test
    void should_end_dead_without_retrying_when_tracking_expires_with_sifen_still_pending() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "PENDIENTE_ENVIO"));
        Operation op = issue(ref);
        drain();
        trackingStartedHoursAgo(op.id(), 73);
        stubFind(ref, document(ref, "EN_PROCESO"));
        makeDue(op.id());

        drain();

        Operation dead = reload(op.id());
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("tracking-expired");
        assertThat(dead.lastError().kind()).isEqualTo(ErrorKind.EXPIRED);
        assertThat(count(findsOf(ref))).isEqualTo(1);
    }

    @Test
    void should_adopt_without_resending_when_submit_times_out_and_verify_finds_the_document() {
        String ref = newSourceRef();
        stubSubmit(ref, document(ref, "APROBADO").withFixedDelay(READ_TIMEOUT_MS * 2));
        Operation op = issue(ref);

        drain();
        Operation uncertain = reload(op.id());
        assertThat(uncertain.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(uncertain.lastError().kind()).isEqualTo(ErrorKind.UNCERTAIN);

        stubFind(ref, document(ref, "APROBADO"));
        makeDue(op.id());
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(count(submitsOf(ref))).isEqualTo(1);
        assertThat(count(findsOf(ref))).isEqualTo(1);
    }

    @Test
    void should_resend_with_the_same_transaction_id_when_submit_times_out_and_verify_finds_nothing() {
        String ref = newSourceRef();
        SIFEN.stubFor(post(urlEqualTo("/api/v1/facturas")).withRequestBody(matchingJsonPath("$.transactionId", equalTo(ref)))
                .inScenario(ref).whenScenarioStateIs(Scenario.STARTED).willSetStateTo("lost")
                .willReturn(document(ref, "APROBADO").withFixedDelay(READ_TIMEOUT_MS * 2)));
        SIFEN.stubFor(post(urlEqualTo("/api/v1/facturas")).withRequestBody(matchingJsonPath("$.transactionId", equalTo(ref)))
                .inScenario(ref).whenScenarioStateIs("lost").willReturn(document(ref, "APROBADO")));
        stubFind(ref, sifenError(404, "NOT_FOUND", "No existe"));
        Operation op = issue(ref);

        drain();
        makeDue(op.id());
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(count(findsOf(ref))).isEqualTo(1);
        assertThat(SIFEN.findAll(submitsOf(ref))).hasSize(2)
                .extracting(request -> json.readTree(request.getBodyAsString()))
                .allSatisfy(body -> assertThat(body).isEqualTo(json.readTree(SIFEN.findAll(submitsOf(ref)).getFirst().getBodyAsString())));
    }

    @Test
    void should_wait_without_counting_an_attempt_and_verify_before_resending_when_emission_is_in_progress() {
        String ref = newSourceRef();
        stubSubmit(ref, sifenError(409, "EMISION_EN_CURSO", "La emision sigue en curso").withHeader("Retry-After", "30"));
        Operation op = issue(ref);

        drain();
        Operation waiting = reload(op.id());
        assertThat(waiting.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(waiting.attempts()).isZero();
        assertThat(waiting.lastError().kind()).isEqualTo(ErrorKind.IN_PROGRESS);
        assertThat(Duration.between(Instant.now(), nextRunAt(op.id())).toSeconds()).isCloseTo(30, within(3L));

        stubFind(ref, document(ref, "APROBADO"));
        makeDue(op.id());
        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(count(submitsOf(ref))).isEqualTo(1);
    }

    @Test
    void should_end_dead_for_human_review_when_sifen_reports_an_idempotency_conflict() {
        String ref = newSourceRef();
        stubSubmit(ref, sifenError(409, "IDEMPOTENCY_CONFLICT", "transactionId reutilizado con otro payload"));
        Operation op = issue(ref);

        drain();

        Operation dead = reload(op.id());
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("idempotency-conflict");
        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("DEAD");
        assertThat(count(submitsOf(ref))).isEqualTo(1);
    }

    @Test
    void should_end_dead_with_credentials_reason_when_sifen_rejects_the_api_key() {
        String ref = newSourceRef();
        stubSubmit(ref, sifenError(401, "HMAC_INVALID", "Firma invalida"));
        Operation op = issue(ref);

        drain();

        Operation dead = reload(op.id());
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("credentials");
        assertThat(dead.lastError().kind()).isEqualTo(ErrorKind.AUTH);
    }

    @Test
    void should_end_dead_for_replay_when_the_sifen_plan_limit_is_exceeded() {
        String ref = newSourceRef();
        stubSubmit(ref, sifenError(402, "PLAN_LIMIT_EXCEEDED", "Limite mensual del plan alcanzado"));
        Operation op = issue(ref);

        drain();

        Operation dead = reload(op.id());
        assertThat(dead.status()).isEqualTo(OperationStatus.DEAD);
        assertThat(dead.deadReason()).isEqualTo("plan-limit");
        assertThat(dead.lastError().kind()).isEqualTo(ErrorKind.PLAN_LIMIT);
        assertThat(dead.lastError().code()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(invoiceState(ref).get("status").asString()).isEqualTo("DEAD");
        assertThat(count(submitsOf(ref))).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"400, VALIDATION_ERROR", "409, BUSINESS_RULE", "422, UNPROCESSABLE"})
    void should_fail_without_retry_and_expose_sifen_detail_when_sifen_refuses_the_request(int status, String code) {
        String ref = newSourceRef();
        stubSubmit(ref, sifenError(status, code, "receptor.numeroDocumento invalido"));
        Operation op = issue(ref);

        drain();

        Operation failed = reload(op.id());
        assertThat(failed.status()).isEqualTo(OperationStatus.FAILED);
        assertThat(failed.lastError().kind()).isEqualTo(ErrorKind.REJECTED);
        JsonNode state = invoiceState(ref);
        assertThat(state.get("status").asString()).isEqualTo("FAILED");
        assertThat(state.get("error").get("code").asString()).isEqualTo(code);
        assertThat(state.get("error").get("detail").asString()).isEqualTo("receptor.numeroDocumento invalido");
        assertThat(count(submitsOf(ref))).isEqualTo(1);
    }

    @Test
    void should_end_failed_and_report_rejected_with_sifen_code_when_sifen_rejects_the_document() {
        String ref = newSourceRef();
        stubSubmit(ref, aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                {"transactionId":"%s","cdc":"%s","estado":"RECHAZADO","codigoRespuesta":"0160",
                 "mensajeRespuesta":"XML mal formado","total":4400}""".formatted(ref, cdcFor(ref))));
        Operation op = issue(ref);

        drain();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.FAILED);
        JsonNode state = invoiceState(ref);
        assertThat(state.get("status").asString()).isEqualTo("REJECTED");
        assertThat(state.get("providerStatus").asString()).isEqualTo("RECHAZADO");
        assertThat(state.get("providerCode").asString()).isEqualTo("0160");
        assertThat(state.get("providerMessage").asString()).isEqualTo("XML mal formado");
    }

    @Test
    void should_retry_after_the_delay_sifen_asks_when_rate_limited() {
        String ref = newSourceRef();
        stubSubmit(ref, sifenError(429, "RATE_LIMIT_EXCEEDED", "Demasiadas solicitudes").withHeader("Retry-After", "60"));
        Operation op = issue(ref);

        drain();

        Operation limited = reload(op.id());
        assertThat(limited.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(limited.lastError().kind()).isEqualTo(ErrorKind.RATE_LIMITED);
        assertThat(Duration.between(Instant.now(), nextRunAt(op.id())).toSeconds()).isCloseTo(60, within(3L));
    }
}
