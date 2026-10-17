package com.mindtechpy.nexus.application.saga.order;

import com.mindtechpy.nexus.application.port.out.ConnectorException;
import com.mindtechpy.nexus.application.port.out.GiftHubPort;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code [NEXUS-ORDER]} POS_CANCELLED by branch: GiftHub cancelled the order and the POS sale is voided only when safe. */
class OrderStatusSagaTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final String ORDER_ID = "ord-1";

    private final PosPort pos = mock(PosPort.class);
    private final GiftHubPort giftHub = mock(GiftHubPort.class);
    private final OrderStatusSaga saga = new OrderStatusSaga(pos, giftHub);

    @Test
    void should_void_a_collected_sale_when_gifthub_cancelled_and_refunded_the_order() {
        posSale("COMPLETED");
        giftHubOrder("CANCELLED", "REFUNDED");
        when(pos.cancelOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID), any())).thenReturn(Optional.of(sale("CANCELLED")));

        StepResult result = saga.execute(context());

        assertThat(((StepResult.Completed) result).externalRef()).isEqualTo("POS_CANCELLED");
        verify(pos).cancelOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID), eq("Pedido cancelado en GiftHub"));
    }

    @Test
    void should_end_in_disagreement_without_calling_the_pos_when_gifthub_did_not_refund_a_collected_sale() {
        posSale("COMPLETED");
        giftHubOrder("CANCELLED", "PAID");

        ConnectorError error = failure(saga.execute(context()));

        assertThat(error.kind()).isEqualTo(ErrorKind.REJECTED);
        assertThat(error.code()).isEqualTo("gifthub-payment-not-refunded");
        assertThatThrownBy(() -> saga.compensate(context(), error)).isInstanceOf(ManualReconciliationRequired.class);
        verify(pos, never()).cancelOrder(any(), any(), any(), any(), any());
    }

    @Test
    void should_end_in_disagreement_asking_for_a_credit_note_when_the_pos_refuses_an_invoiced_sale() {
        posSale("COMPLETED");
        giftHubOrder("CANCELLED", "REFUNDED");
        when(pos.cancelOrder(any(), any(), any(), any(), any())).thenThrow(posConflict(ErrorKind.REJECTED, "invoiced"));

        ConnectorError error = failure(saga.execute(context()));

        assertThat(error.code()).isEqualTo("invoiced");
        assertThat(error.detail()).isEqualTo("Venta facturada: requiere nota de crédito en el POS");
        assertThatThrownBy(() -> saga.compensate(context(), error)).isInstanceOf(ManualReconciliationRequired.class)
                .hasMessage("Venta facturada: requiere nota de crédito en el POS");
    }

    @Test
    void should_retry_without_compensation_when_the_pos_has_no_open_register_for_the_refund() {
        posSale("COMPLETED");
        giftHubOrder("CANCELLED", "REFUNDED");
        when(pos.cancelOrder(any(), any(), any(), any(), any())).thenThrow(posConflict(ErrorKind.TRANSIENT, "cash-session-closed"));

        ConnectorError error = failure(saga.execute(context()));

        assertThat(error.kind()).isEqualTo(ErrorKind.TRANSIENT);
        assertThat(error.code()).isEqualTo("cash-session-closed");
        assertThat(saga.compensate(context(), error)).isFalse();
    }

    @Test
    void should_end_in_disagreement_when_the_pos_says_the_sale_is_not_cancellable() {
        posSale("PENDING");
        when(pos.cancelOrder(any(), any(), any(), any(), any())).thenThrow(posConflict(ErrorKind.REJECTED, "not-cancellable"));

        ConnectorError error = failure(saga.execute(context()));

        assertThat(error.code()).isEqualTo("not-cancellable");
        assertThat(error.detail()).contains("el POS no puede anular la venta");
    }

    @Test
    void should_succeed_without_calling_the_pos_or_gifthub_when_the_sale_is_already_cancelled() {
        posSale("CANCELLED");

        StepResult result = saga.execute(context());

        assertThat(((StepResult.Completed) result).externalRef()).isEqualTo("POS_CANCELLED");
        verify(pos, never()).cancelOrder(any(), any(), any(), any(), any());
        verify(giftHub, never()).findOrder(any(), any(), any());
    }

    @Test
    void should_succeed_without_calling_the_pos_when_it_has_no_sale() {
        when(pos.findOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID))).thenReturn(Optional.empty());

        StepResult result = saga.execute(context());

        assertThat(((StepResult.Completed) result).externalRef()).isEqualTo("POS_NOT_FOUND");
        verify(pos, never()).cancelOrder(any(), any(), any(), any(), any());
    }

    @Test
    void should_void_a_pending_sale_without_reading_gifthub() {
        posSale("PENDING");
        when(pos.cancelOrder(any(), any(), any(), any(), any())).thenReturn(Optional.of(sale("CANCELLED")));

        StepResult result = saga.execute(context());

        assertThat(((StepResult.Completed) result).externalRef()).isEqualTo("POS_CANCELLED");
        verify(giftHub, never()).findOrder(any(), any(), any());
    }

    @Test
    void should_retry_without_calling_the_pos_when_gifthub_is_down_for_a_collected_sale() {
        posSale("COMPLETED");
        when(giftHub.findOrder(any(), any(), eq(ORDER_ID)))
                .thenThrow(new ConnectorException(new ConnectorError(ErrorKind.TRANSIENT, "gifthub-http-503", "caído", 503, null)));

        ConnectorError error = failure(saga.execute(context()));

        assertThat(error.kind()).isEqualTo(ErrorKind.TRANSIENT);
        verify(pos, never()).cancelOrder(any(), any(), any(), any(), any());
    }

    private void posSale(String status) {
        when(pos.findOrder(any(), any(), eq("GIFTHUB"), eq(ORDER_ID))).thenReturn(Optional.of(sale(status)));
    }

    private void giftHubOrder(String status, String paymentStatus) {
        when(giftHub.findOrder(any(), any(), eq(ORDER_ID)))
                .thenReturn(Optional.of(new GiftHubPort.Order(ORDER_ID, status, paymentStatus, "PICKUP")));
    }

    private static PosPort.Order sale(String status) {
        return new PosPort.Order("sale-1", "SALE-00000042", 91000, status);
    }

    private static ConnectorException posConflict(ErrorKind kind, String code) {
        return new ConnectorException(new ConnectorError(kind, code, "El POS rechazó la anulación", 409, null));
    }

    private static StepContext context() {
        UUID giftHubId = UUID.randomUUID();
        UUID posId = UUID.randomUUID();
        Integration integration = Integration.create(TENANT, Flow.ORDER, "pedidos", giftHubId, posId, null, Instant.now());
        Operation op = Operation.create(TENANT, integration.id(), OperationKind.ORDER_STATUS, ORDER_ID + ":POS_CANCELLED",
                Map.of("orderId", ORDER_ID, "target", "POS_CANCELLED"), 5, null, Instant.now()).atStep(OrderStatusSaga.SYNC);
        return new StepContext(op, integration, endpoint(giftHubId, ConnectorType.GIFTHUB), endpoint(posId, ConnectorType.POS));
    }

    private static Endpoint endpoint(UUID id, ConnectorType type) {
        return new Endpoint(new Connection(id, TENANT, type, type.name().toLowerCase(), "https://" + type.name().toLowerCase() + ".test",
                new byte[0], Map.of(), null, null), new ConnectionCredentials(Map.of("apiKey", "k")));
    }

    private static ConnectorError failure(StepResult result) {
        assertThat(result).isInstanceOf(StepResult.Failed.class);
        return ((StepResult.Failed) result).error();
    }
}
