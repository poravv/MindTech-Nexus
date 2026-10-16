package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The POS "sync now" request: which integrations it starts and with which idempotency key. */
class RunCatalogSyncTest {

    private static final Instant NOW = Instant.parse("2026-10-16T14:05:30Z");
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final IntegrationRepository integrations = mock(IntegrationRepository.class);
    private final OperationRepository operations = mock(OperationRepository.class);
    private final StartOperation startOperation = mock(StartOperation.class);
    private final ClockPort clock = () -> NOW;
    private final TransactionRunner tx = new TransactionRunner() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
    private final RunCatalogSync runCatalogSync = new RunCatalogSync(integrations, operations, startOperation, null, clock, tx);

    @Test
    void should_answer_404_when_the_tenant_has_no_enabled_catalog_integration() {
        when(integrations.findAll(tenant)).thenReturn(List.of());

        assertThatThrownBy(() -> runCatalogSync.requestFromPos(tenant))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.NOT_FOUND);
                    assertThat(e.code()).isEqualTo("catalog-integration-not-found");
                });
    }

    @Test
    void should_start_a_run_keyed_by_integration_and_20_second_window() {
        Integration integration = catalogIntegration();
        when(integrations.findAll(tenant)).thenReturn(List.of(integration));
        Operation operation = operationOf(integration);
        when(startOperation.execute(any())).thenReturn(new StartOperation.Started(operation, true));

        List<RunCatalogSync.Requested> requested = runCatalogSync.requestFromPos(tenant);

        ArgumentCaptor<StartOperation.Command> command = ArgumentCaptor.forClass(StartOperation.Command.class);
        verify(startOperation).execute(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo(integration.id() + ":pos:2026101614051");
        assertThat(command.getValue().kind()).isEqualTo(OperationKind.CATALOG_SYNC);
        assertThat(command.getValue().payload()).isEqualTo(Map.of("trigger", "pos"));
        assertThat(requested).containsExactly(new RunCatalogSync.Requested(integration.id(), operation.id(), true));
    }

    @Test
    void should_report_created_false_when_the_run_of_this_window_already_exists() {
        Integration integration = catalogIntegration();
        when(integrations.findAll(tenant)).thenReturn(List.of(integration));
        when(startOperation.execute(any())).thenReturn(new StartOperation.Started(operationOf(integration), false));

        assertThat(runCatalogSync.requestFromPos(tenant)).singleElement().extracting(RunCatalogSync.Requested::created).isEqualTo(false);
    }

    @Test
    void should_answer_409_without_starting_anything_when_a_run_is_in_progress() {
        Integration integration = catalogIntegration();
        when(integrations.findAll(tenant)).thenReturn(List.of(integration));
        when(operations.hasUnfinished(tenant, OperationKind.CATALOG_SYNC, integration.targetConnectionId())).thenReturn(true);

        assertThatThrownBy(() -> runCatalogSync.requestFromPos(tenant))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("catalog-run-in-progress");
                });
        verify(startOperation, never()).execute(any());
    }

    private Integration catalogIntegration() {
        return Integration.create(tenant, Flow.CATALOG, "gifthub-catalog", UUID.randomUUID(), UUID.randomUUID(), null, NOW);
    }

    private Operation operationOf(Integration integration) {
        return Operation.create(tenant, integration.id(), OperationKind.CATALOG_SYNC, "key", Map.of("trigger", "pos"), 3, null, NOW);
    }
}
