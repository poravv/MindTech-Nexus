import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Flow } from '../../api/integrations.api';
import { OperationsApi, OperationStatus } from '../../api/operations.api';
import { AuthService } from '../../core/auth/auth.service';
import { problemMessage } from '../../core/problem';
import { SECTION_ROLES } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { Icon } from '../../shared/ui/icon';
import { STATUS_META, StatusBadge } from '../../shared/ui/status-badge';
import { FLOW_META, HEALTHS } from '../integrations/health';
import { loadIntegrationData, toRows } from '../integrations/integration-data';

const DAY_MS = 24 * 60 * 60 * 1000;
/** Donut order: outcomes first, then work in flight, then failures (keeps adjacent hues apart). */
const DONUT_ORDER: OperationStatus[] = ['SUCCEEDED', 'RUNNING', 'RETRY', 'PENDING', 'FAILED', 'DEAD'];
const STATUS_FILL: Record<OperationStatus, string> = {
  SUCCEEDED: 'var(--chart-ok)',
  RUNNING: 'var(--chart-info)',
  RETRY: 'var(--chart-warn)',
  PENDING: 'var(--chart-neutral)',
  FAILED: 'var(--chart-serious)',
  DEAD: 'var(--chart-danger)',
};
const RADIUS = 42;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
/** Surface gap between donut segments, in the same units as the circumference. */
const GAP = 1.6;

@Component({
  selector: 'app-overview-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon, StatusBadge],
  templateUrl: './overview-page.html',
  styleUrl: './overview-page.scss',
})
export default class OverviewPage {
  private readonly operationsApi = inject(OperationsApi);
  private readonly auth = inject(AuthService);
  protected readonly scope = inject(TenantScope);

  protected readonly summary = rxResource({
    params: () => ({ tenantId: this.scope.tenantId() }),
    stream: () => this.operationsApi.summary(new Date(Date.now() - DAY_MS).toISOString()),
  });
  /** Viewers only see the dashboard and operations (spec §5.2), so they skip the integrations call. */
  protected readonly showIntegrations = computed(() => {
    const role = this.auth.role();
    return role !== null && SECTION_ROLES.integrations.includes(role);
  });
  private readonly loadIntegrations = loadIntegrationData();
  protected readonly integrations = rxResource({
    params: () => (this.showIntegrations() && !this.scope.needsTenant() ? { tenantId: this.scope.tenantId() } : undefined),
    stream: () => this.loadIntegrations(),
  });

  protected readonly totals = computed(() => {
    if (!this.summary.hasValue()) {
      return null;
    }
    const by = this.summary.value().byStatus;
    const count = (status: OperationStatus) => by[status] ?? 0;
    const total = DONUT_ORDER.reduce((sum, status) => sum + count(status), 0);
    const finished = count('SUCCEEDED') + count('FAILED') + count('DEAD');
    return {
      total,
      dead: this.summary.value().dead,
      inFlight: count('PENDING') + count('RUNNING') + count('RETRY'),
      retrying: count('RETRY'),
      successRate: finished ? Math.round((count('SUCCEEDED') / finished) * 1000) / 10 : null,
    };
  });

  protected readonly donut = computed(() => {
    const totals = this.totals();
    if (!this.summary.hasValue() || !totals?.total) {
      return [];
    }
    const by = this.summary.value().byStatus;
    let offset = 0;
    return DONUT_ORDER.map((status) => {
      const value = by[status] ?? 0;
      const length = (value / totals.total) * CIRCUMFERENCE;
      const segment = {
        status,
        value,
        share: Math.round((value / totals.total) * 1000) / 10,
        label: STATUS_META[status].label,
        fill: STATUS_FILL[status],
        dash: `${Math.max(length - GAP, 0.5)} ${CIRCUMFERENCE}`,
        offset: -offset,
      };
      offset += length;
      return segment;
    });
  });

  protected readonly flows = computed(() => {
    if (!this.summary.hasValue()) {
      return [];
    }
    const by = this.summary.value().byFlow;
    const max = Math.max(1, ...Object.values(by).map((value) => value ?? 0));
    return (Object.keys(FLOW_META) as Flow[]).map((flow) => ({
      flow,
      ...FLOW_META[flow],
      value: by[flow] ?? 0,
      width: ((by[flow] ?? 0) / max) * 100,
    }));
  });

  protected readonly integrationRows = computed(() => (this.integrations.hasValue() ? toRows(this.integrations.value()) : []));
  protected readonly healthCounts = computed(() =>
    HEALTHS.map((health) => ({ health, count: this.integrationRows().filter((row) => row.health === health).length })),
  );
  protected readonly failing = computed(() => this.integrationRows().filter((row) => row.health === 'DEGRADED'));

  protected readonly flowMeta = FLOW_META;
  protected readonly radius = RADIUS;

  protected unavailable(error: unknown): string {
    return error instanceof HttpErrorResponse && error.status === 404
      ? 'Todavía no disponible en esta versión de la API.'
      : problemMessage(error, 'No se pudo cargar.');
  }
}
