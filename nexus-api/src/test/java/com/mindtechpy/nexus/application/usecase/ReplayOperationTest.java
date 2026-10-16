package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Replaying a dead catalog run is a new run to that GiftHub: it takes the target lock and needs it idle. */
class ReplayOperationTest {

    private static final Instant NOW = Instant.parse("2026-10-16T14:05:30Z");
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Actor operator = Actor.user("op", Role.OPERATOR, tenant);
    private final OperationRepository operations = mock(OperationRepository.class);
    private final IntegrationRepository integrations = mock(IntegrationRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final ClockPort clock = () -> NOW;
    private final TransactionRunner tx = new TransactionRunner() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
    private final ReplayOperation replay = new ReplayOperation(operations, integrations, audit, clock, tx);
    private final Integration catalog = Integration.create(tenant, Flow.CATALOG, "gifthub-catalog", UUID.randomUUID(),
            UUID.randomUUID(), null, NOW);
    private final Operation dead = Operation.create(tenant, catalog.id(), OperationKind.CATALOG_SYNC, "key", Map.of(), 3, null, NOW)
            .dead("exhausted", new ConnectorError(ErrorKind.TRANSIENT, "timeout", "x", null, null), NOW);

    @Test
    void should_answer_409_and_not_replay_when_another_run_to_the_same_target_is_unfinished() {
        givenTheDeadRun();
        when(operations.hasUnfinished(tenant, OperationKind.CATALOG_SYNC, catalog.targetConnectionId())).thenReturn(true);

        assertThatThrownBy(() -> replay.execute(operator, tenant, dead.id()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("catalog-run-in-progress");
                });
        verify(operations, never()).update(any(), any());
    }

    @Test
    void should_replay_under_the_target_lock_when_no_other_run_is_unfinished() {
        givenTheDeadRun();
        when(operations.update(any(), any())).thenReturn(true);

        replay.execute(operator, tenant, dead.id());

        var order = inOrder(operations);
        order.verify(operations).lockCatalogTarget(catalog.targetConnectionId());
        order.verify(operations).update(any(), any());
    }

    private void givenTheDeadRun() {
        when(operations.findById(tenant, dead.id())).thenReturn(Optional.of(dead));
        when(integrations.findById(tenant, catalog.id())).thenReturn(Optional.of(catalog));
    }
}
