import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Flow, IntegrationsApi } from '../../api/integrations.api';
import { OPERATION_STATUSES, OperationsApi } from '../../api/operations.api';
import { AuthService } from '../../core/auth/auth.service';
import { problemMessage } from '../../core/problem';
import { IntegrationState, SECTION_ROLES } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { StatusBadge } from '../../shared/ui/status-badge';

const DAY_MS = 24 * 60 * 60 * 1000;
const FLOW_LABELS: Record<Flow, string> = { FISCAL: 'Fiscal', ORDER: 'Pedidos', CATALOG: 'Catálogo' };
const INTEGRATION_STATES: IntegrationState[] = ['ENABLED', 'PAUSED', 'DRAINING', 'DISABLED', 'EMERGENCY_STOP'];

@Component({
  selector: 'app-overview-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, StatusBadge],
  templateUrl: './overview-page.html',
  styleUrl: './overview-page.scss',
})
export default class OverviewPage {
  private readonly operationsApi = inject(OperationsApi);
  private readonly integrationsApi = inject(IntegrationsApi);
  private readonly auth = inject(AuthService);
  protected readonly scope = inject(TenantScope);

  protected readonly summary = rxResource({
    params: () => ({ tenantId: this.scope.tenantId() }),
    stream: () => this.operationsApi.summary(new Date(Date.now() - DAY_MS).toISOString()),
  });
  /** Viewers only see the dashboard and operations (spec §5.2), so they skip the integrations call. */
  private readonly seesIntegrations = computed(() => {
    const role = this.auth.role();
    return role !== null && SECTION_ROLES.integrations.includes(role);
  });
  protected readonly integrations = rxResource({
    params: () => (this.seesIntegrations() && !this.scope.needsTenant() ? { tenantId: this.scope.tenantId() } : undefined),
    stream: () => this.integrationsApi.list(),
  });

  protected readonly statuses = OPERATION_STATUSES;
  protected readonly flowLabels = FLOW_LABELS;
  protected readonly flows = Object.keys(FLOW_LABELS) as Flow[];
  protected readonly states = INTEGRATION_STATES;
  protected readonly showIntegrations = this.seesIntegrations;

  protected readonly integrationCounts = computed(() => {
    const counts = new Map<IntegrationState, number>();
    for (const integration of this.integrations.hasValue() ? this.integrations.value() : []) {
      counts.set(integration.state, (counts.get(integration.state) ?? 0) + 1);
    }
    return counts;
  });

  protected unavailable(error: unknown): string {
    return error instanceof HttpErrorResponse && error.status === 404
      ? 'Todavía no disponible en esta versión de la API.'
      : problemMessage(error, 'No se pudo cargar.');
  }
}
