package com.mindtechpy.nexus.infrastructure.connectors.pos;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.PosPort;
import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.order.InvoiceData;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.infrastructure.connectors.ConnectorHttp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.jsonResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The payment body the POS receives, and how its 400 answers are classified. */
class PosRestClientTest {

    private static final String PATH = "/api/integration/orders/GIFTHUB/ord-1/payments";
    private static final String PAID = """
            {"saleId":"sale-1","saleNumber":"SALE-00000042","status":"COMPLETED","invoiceQueued":true}""";

    private final WireMockServer server = new WireMockServer(options().dynamicPort());
    private final PosRestClient client = new PosRestClient(new ConnectorHttp(JsonMapper.builder().build(), new SimpleMeterRegistry()));
    private Endpoint pos;

    @BeforeEach
    void start() {
        server.start();
        pos = new Endpoint(new Connection(UUID.randomUUID(), new TenantId(UUID.randomUUID()), ConnectorType.POS, "pos",
                server.baseUrl(), new byte[0], Map.of(), null, null), new ConnectionCredentials(Map.of("apiKey", "k")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void should_send_the_invoice_with_ruc_dv_and_business_name_when_the_payment_carries_one() {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(okJson(PAID)));

        pay(new InvoiceData("80012345", "6", "Comercial López S.A."));

        server.verify(postRequestedFor(urlPathEqualTo(PATH)).withRequestBody(equalToJson("""
                {"method":"CASH","amount":91000,"reference":"GiftHub #BH-1",
                 "invoice":{"ruc":"80012345","dv":"6","businessName":"Comercial López S.A."}}""")));
    }

    @Test
    void should_send_no_invoice_member_when_the_payment_has_none() {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(okJson(PAID)));

        pay(null);

        server.verify(postRequestedFor(urlPathEqualTo(PATH)).withRequestBody(equalToJson("""
                {"method":"CASH","amount":91000,"reference":"GiftHub #BH-1"}""")));
    }

    @Test
    void should_keep_the_pos_code_when_the_payment_is_answered_400() {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(jsonResponse("""
                {"type":"about:blank","status":400,"code":"invalid-ruc","detail":"El DV no corresponde al RUC"}""", 400)));

        assertThatThrownBy(() -> pay(new InvoiceData("80012345", "7", "Comercial López S.A.")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.error().kind()).isEqualTo(ErrorKind.REJECTED);
                    assertThat(e.error().code()).isEqualTo("invalid-ruc");
                });
    }

    @Test
    void should_classify_a_400_without_code_as_pos_bad_request() {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(jsonResponse("{\"detail\":\"Body inválido\"}", 400)));

        assertThatThrownBy(() -> pay(null)).isInstanceOfSatisfying(ConnectorException.class,
                e -> assertThat(e.error().code()).isEqualTo("pos-bad-request"));
    }

    private void pay(InvoiceData invoice) {
        client.payChannelOrder(pos, Duration.ofSeconds(5), "GIFTHUB:ord-1:paid", "GIFTHUB", "ord-1",
                new PosPort.Payment("CASH", 91000, "GiftHub #BH-1", invoice));
    }
}
