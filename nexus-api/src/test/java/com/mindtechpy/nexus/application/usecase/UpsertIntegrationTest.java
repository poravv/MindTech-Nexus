package com.mindtechpy.nexus.application.usecase;

import com.mindtechpy.nexus.application.port.out.AuditRepository;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.ConnectionRepository;
import com.mindtechpy.nexus.application.port.out.IntegrationRepository;
import com.mindtechpy.nexus.application.port.out.TenantRepository;
import com.mindtechpy.nexus.application.port.out.TransactionRunner;
import com.mindtechpy.nexus.domain.connection.Connection;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Role;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.integration.Integration;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.domain.tenant.Tenant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One enabled CATALOG integration per target GiftHub connection. */
class UpsertIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-16T14:05:30Z");
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Actor admin = Actor.user("admin", Role.PLATFORM_ADMIN, null);
    private final IntegrationRepository integrations = mock(IntegrationRepository.class);
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final ClockPort clock = () -> NOW;
    private final TransactionRunner tx = new TransactionRunner() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
    private final UpsertIntegration upsert = new UpsertIntegration(integrations, connections, tenants, mock(AuditRepository.class), clock, tx);
    private final UUID gifthub = UUID.randomUUID();

    @Test
    void should_answer_409_when_another_enabled_catalog_integration_targets_the_same_connection() {
        when(tenants.findById(tenant)).thenReturn(Optional.of(mock(Tenant.class)));
        when(connections.findById(any(), any())).thenReturn(Optional.of(mock(Connection.class)));
        when(integrations.findAll(tenant)).thenReturn(List.of(catalogTo(gifthub, "catalog-a")));

        assertThatThrownBy(() -> upsert.create(admin, tenant, Flow.CATALOG, draftTo(gifthub, "catalog-b")))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT);
                    assertThat(e.code()).isEqualTo("catalog-target-in-use");
                });
    }

    @Test
    void should_create_the_catalog_integration_when_the_other_one_targets_another_connection() {
        when(tenants.findById(tenant)).thenReturn(Optional.of(mock(Tenant.class)));
        when(connections.findById(any(), any())).thenReturn(Optional.of(mock(Connection.class)));
        when(integrations.findAll(tenant)).thenReturn(List.of(catalogTo(UUID.randomUUID(), "catalog-a")));

        Integration created = upsert.create(admin, tenant, Flow.CATALOG, draftTo(gifthub, "catalog-b"));

        verify(integrations).insert(created);
    }

    private Integration catalogTo(UUID target, String code) {
        return Integration.create(tenant, Flow.CATALOG, code, UUID.randomUUID(), target, null, NOW);
    }

    private UpsertIntegration.Draft draftTo(UUID target, String code) {
        return new UpsertIntegration.Draft(code, UUID.randomUUID(), target, null);
    }
}
