package com.mindtechpy.nexus.application.saga.order;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.PosPort;
import com.mindtechpy.nexus.application.saga.ManualReconciliationRequired;
import com.mindtechpy.nexus.application.saga.StepContext;
import com.mindtechpy.nexus.application.saga.StepContext.Endpoint;
import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.connection.ConnectionCredentials;
import com.mindtechpy.nexus.domain.connection.ConnectorType;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code [NEXUS-ORDER]} ORDER_PAID by branch: order gate, effect validation, remote duplicate as success, and the dead ends. */
class OrderPaidSagaTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final String ORDER_ID = "ord-1";
    private static final String KEY = "GIFTHUB:" + ORDER_ID + ":paid";
    private static final PosPort.Order PENDING_SALE = new PosPort.Order("sale-1", "SALE-00000042", 91000, "PENDING");
    private static final PosPort.Order COMPLETED_SALE = new PosPort.Order("sale-1", "SALE-00000042", 91000, "COMPLETED");
    private static final PosPort.PaidSale PAID = new PosPort.PaidSale("sale-1", "SALE-00000042", "COMPLETED", true);

    private final PosPort pos = mock(PosPort.class);
    private final OperationRepository operations = mock(OperationRepository.class);
    private final OrderPaidSaga saga = new OrderPaidSaga(pos, operations);

    @BeforeEach
    void saleExists() {
        createOrderEnded(Operation.create(TENANT, UUID.randomUUID(), OperationKind.CREATE_ORDER, ORDER_ID, Map.of(), 5, null, Instant.now())
                .atStep("NOTIFY_GIFTHUB").succeeded(Map.of("saleId", "sale-1", "saleNumber", "SALE-00000042"), Instant.now()));
    }

    @Test
    void should_pay_the_pos_sale_with_gifthub_amount_method_and_reference_when_the_order_is_paid() {
        when(pos.payChannelOrder(any(), any(), eq(KEY), eq("GIFTHUB"), eq(ORDER_ID), any())).thenReturn(PAID);

        StepResult result = saga.execute(context(paid("CASH")));

        assertThat(result).isInstanceOf(StepResult.Completed.class);
        StepResult.Completed done = (StepResult.Completed) result;
        assertThat(done.externalRef()).isEqualTo("sale-1");
        assertThat(done.result()).containsEntry("saleNumber", "SALE-00000042").containsEntry("method", "CASH")
                .containsEntry("amount", 91000L).containsEntry("invoiceQueued", true);
        verify(pos).payChannelOrder(any(), any(), eq(KEY), eq("GIFTHUB"), eq(ORDER_ID),
                eq(new PosPort.Payment("CASH", 91000, "GiftHub #BH-1")));
    }

    @Test
    void should_send_other_when_gifthub_has_no_method_the_pos_knows() {
        when(pos.payChannelOrder(any(), any(), any(), any(), any(), any())).thenReturn(PAID);

        saga.execute(context(paid(null)));
        saga.execute(context(paid("OTHER")));
        saga.execute(context(paid("TRANSFER")));

        verify(pos, times(2)).payChannelOrder(any(), any(), any(), any(), any(),
                eq(new PosPort.Payment("OTHER", 91000, "GiftHub #BH-1")));
        verify(pos).payChannelOrder(any(), any(), any(), any(), any(), eq(new PosPort.Payment("TRANSFER", 91000, "GiftHub #BH-1")));
    }

    @Test
    void should_retry_later_without_calling_the_pos_while_create_order_is_still_running() {
        createOrderEnded(Operation.create(TENANT, UUID.randomUUID(), OperationKind.CREATE_ORDER, ORDER_ID, Map.of(), 5, null, Instant.now()));

        StepResult result = saga.execute(context(paid("CASH")));

        assertThat(failure(result).kind()).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(failure(result).code()).isEqualTo("sale-pending");
        verify(pos, never()).payChannelOrder(any(), any(), any(), any(), any(), any());
    }

    @Test
    void should_retry_later_when_order_created_has_not_arrived_yet() {
        when(operations.findByKey(TENANT, OperationKind.CREATE_ORDER, ORDER_ID)).thenReturn(Optional.empty());

        assertThat(failure(saga.execute(context(paid("CASH")))).code()).isEqualTo("sale-pending");
    }

    @Test
    void should_skip_with_no_sale_when_create_order_was_rejected_and_the_pos_has_no_sale() {
        createOrderEnded(Operation.create(TENANT, UUID.randomUUID(), OperationKind.CREATE_ORDER, ORDER_ID, Map.of(), 5, null, Instant.now())
                .atStep("CREATE_ORDER").failed(ConnectorError.of(ErrorKind.REJECTED, "insufficient-stock", "sin stock"), Instant.now()));

        StepResult result = saga.execute(context(paid("CASH")));

        assertThat(((StepResult.Completed) result).result()).containsEntry("outcome", "SKIPPED").containsEntry("reason", "no-sale");
        verify(pos).findOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID));
        verify(pos, never()).payChannelOrder(any(), any(), any(), any(), any(), any());
    }

    @Test
    void should_retry_while_create_order_is_dead_and_pay_once_its_replay_creates_the_sale() {
        Operation createDead = Operation.create(TENANT, UUID.randomUUID(), OperationKind.CREATE_ORDER, ORDER_ID, Map.of(), 5, null,
                Instant.now()).atStep("CREATE_ORDER").dead("attempts-exhausted",
                ConnectorError.of(ErrorKind.TRANSIENT, "connect-failed", "POS caído"), Instant.now());
        createOrderEnded(createDead);

        ConnectorError waiting = failure(saga.execute(context(paid("CASH"))));

        assertThat(waiting.kind()).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(waiting.code()).isEqualTo("sale-pending");
        verify(pos, never()).payChannelOrder(any(), any(), any(), any(), any(), any());

        createOrderEnded(createDead.replayed(Instant.now()).atStep("NOTIFY_GIFTHUB")
                .succeeded(Map.of("saleId", "sale-1", "saleNumber", "SALE-00000042"), Instant.now()));
        when(pos.payChannelOrder(any(), any(), eq(KEY), any(), any(), any())).thenReturn(PAID);

        assertThat(saga.execute(context(paid("CASH")))).isInstanceOf(StepResult.Completed.class);
        verify(pos).payChannelOrder(any(), any(), eq(KEY), eq("GIFTHUB"), eq(ORDER_ID), any());
    }

    @Test
    void should_pay_when_create_order_has_no_sale_id_but_the_pos_has_the_sale() {
        createOrderEnded(Operation.create(TENANT, UUID.randomUUID(), OperationKind.CREATE_ORDER, ORDER_ID, Map.of(), 5, null, Instant.now())
                .atStep("CREATE_ORDER").dead("attempts-exhausted", ConnectorError.of(ErrorKind.UNCERTAIN, "no-response", "timeout"),
                        Instant.now()));
        when(pos.findOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID))).thenReturn(Optional.of(PENDING_SALE));
        when(pos.payChannelOrder(any(), any(), eq(KEY), any(), any(), any())).thenReturn(PAID);

        StepResult result = saga.execute(context(paid("CASH")));

        assertThat(((StepResult.Completed) result).externalRef()).isEqualTo("sale-1");
        verify(pos).payChannelOrder(any(), any(), eq(KEY), eq("GIFTHUB"), eq(ORDER_ID), any());
    }

    @Test
    void should_skip_with_not_paid_when_the_event_does_not_say_paid() {
        Map<String, Object> payload = paid("CASH");
        payload.put("payment_status", "PENDING");

        StepResult result = saga.execute(context(payload));

        assertThat(((StepResult.Completed) result).result()).containsEntry("reason", "not-paid");
        verify(pos, never()).payChannelOrder(any(), any(), any(), any(), any(), any());
    }

    @Test
    void should_succeed_when_the_pos_says_not_pending_and_the_sale_is_already_completed() {
        when(pos.payChannelOrder(any(), any(), any(), any(), any(), any())).thenThrow(rejected("not-pending"));
        when(pos.findOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID))).thenReturn(Optional.of(COMPLETED_SALE));

        StepResult result = saga.execute(context(paid("CASH")));

        assertThat(result).isInstanceOf(StepResult.Completed.class);
        assertThat(((StepResult.Completed) result).result()).containsEntry("saleNumber", "SALE-00000042").containsEntry("invoiceQueued", false);
    }

    @Test
    void should_ask_for_manual_reconciliation_when_not_pending_but_the_sale_is_not_completed() {
        when(pos.payChannelOrder(any(), any(), any(), any(), any(), any())).thenThrow(rejected("not-pending"));
        when(pos.findOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID))).thenReturn(Optional.of(PENDING_SALE));

        ConnectorError error = failure(saga.execute(context(paid("CASH"))));

        assertThat(error.code()).isEqualTo("not-pending");
        assertThatThrownBy(() -> saga.compensate(context(paid("CASH")), error)).isInstanceOf(ManualReconciliationRequired.class);
    }

    @Test
    void should_end_in_manual_reconciliation_without_undoing_anything_when_the_pos_rejects_the_amount() {
        when(pos.payChannelOrder(any(), any(), any(), any(), any(), any())).thenThrow(rejected("amount-mismatch"));

        ConnectorError error = failure(saga.execute(context(paid("CASH"))));

        assertThat(error.kind()).isEqualTo(ErrorKind.REJECTED);
        assertThat(error.code()).isEqualTo("amount-mismatch");
        assertThatThrownBy(() -> saga.compensate(context(paid("CASH")), error)).isInstanceOf(ManualReconciliationRequired.class)
                .hasMessageContaining("amount-mismatch");
        verify(pos, never()).cancelOrder(any(), any(), any(), any(), any());
    }

    @Test
    void should_retry_with_backoff_when_the_cash_session_is_closed() {
        when(pos.payChannelOrder(any(), any(), any(), any(), any(), any())).thenThrow(new ConnectorException(
                ConnectorError.of(ErrorKind.TRANSIENT, "cash-session-closed", "Caja cerrada")));

        ConnectorError error = failure(saga.execute(context(paid("CASH"))));

        assertThat(error.kind()).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(error.code()).isEqualTo("cash-session-closed");
        saga.compensate(context(paid("CASH")), error);
    }

    @Test
    void should_adopt_the_completed_sale_without_resending_when_verification_after_a_timeout_finds_it_paid() {
        when(pos.findOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID))).thenReturn(Optional.of(COMPLETED_SALE));

        StepResult result = saga.execute(uncertain(context(paid("CASH"))));

        assertThat(result).isInstanceOf(StepResult.Completed.class);
        verify(pos, never()).payChannelOrder(any(), any(), any(), any(), any(), any());
    }

    @Test
    void should_resend_with_the_same_key_when_verification_after_a_timeout_finds_the_sale_still_pending() {
        when(pos.findOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID))).thenReturn(Optional.of(PENDING_SALE));
        when(pos.payChannelOrder(any(), any(), eq(KEY), any(), any(), any())).thenReturn(PAID);

        assertThat(saga.execute(uncertain(context(paid("CASH"))))).isInstanceOf(StepResult.Completed.class);
    }

    @Test
    void should_fail_uncertain_when_the_pos_answers_200_but_the_sale_did_not_close() {
        when(pos.payChannelOrder(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PosPort.PaidSale("sale-1", "SALE-00000042", "PENDING", false));

        ConnectorError error = failure(saga.execute(context(paid("CASH"))));

        assertThat(error.kind()).isEqualTo(ErrorKind.UNCERTAIN);
        assertThat(error.code()).isEqualTo("pos-payment-not-applied");
    }

    private void createOrderEnded(Operation created) {
        when(operations.findByKey(TENANT, OperationKind.CREATE_ORDER, ORDER_ID)).thenReturn(Optional.of(created));
    }

    private static Map<String, Object> paid(String method) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", ORDER_ID);
        payload.put("number", "BH-1");
        payload.put("payment_status", "PAID");
        payload.put("payment_method", method);
        payload.put("total_pyg", 91000);
        return payload;
    }

    private static StepContext context(Map<String, Object> payload) {
        UUID giftHub = UUID.randomUUID();
        UUID posId = UUID.randomUUID();
        Integration integration = Integration.create(TENANT, Flow.ORDER, "pedidos", giftHub, posId, null, Instant.now());
        Operation op = Operation.create(TENANT, integration.id(), OperationKind.ORDER_PAID, ORDER_ID + ":paid", payload, 5, null,
                Instant.now()).atStep(OrderPaidSaga.PAY_POS);
        return new StepContext(op, integration, endpoint(giftHub, ConnectorType.GIFTHUB), endpoint(posId, ConnectorType.POS));
    }

    private static StepContext uncertain(StepContext context) {
        return new StepContext(context.operation().retrying(ConnectorError.of(ErrorKind.UNCERTAIN, "no-response", "timeout"),
                Instant.now(), true), context.integration(), context.source(), context.target());
    }

    private static Endpoint endpoint(UUID id, ConnectorType type) {
        return new Endpoint(new Connection(id, TENANT, type, type.name().toLowerCase(), "https://" + type.name().toLowerCase() + ".test",
                new byte[0], Map.of(), null, null), new ConnectionCredentials(Map.of("apiKey", "k")));
    }

    private static ConnectorException rejected(String code) {
        return new ConnectorException(new ConnectorError(ErrorKind.REJECTED, code, "El POS rechazó el pago", 409, null));
    }

    private static ConnectorError failure(StepResult result) {
        assertThat(result).isInstanceOf(StepResult.Failed.class);
        return ((StepResult.Failed) result).error();
    }
}
