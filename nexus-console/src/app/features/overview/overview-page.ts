import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Flow } from '../../api/integrations.api';
import { OperationsApi, OperationStatus } from '../../api/operations.api';
import { AuthService } from '../../core/auth/auth.service';
import { problemMessage } from '../../core/problem';
import { SECTION_ROLES } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { BarItem, BarList } from '../../shared/ui/bar-list';
import { DonutChart, DonutSegment } from '../../shared/ui/donut-chart';
import { Icon } from '../../shared/ui/icon';
import { HEALTH_META, STATUS_META } from '../../shared/ui/status-badge';
import { FLOW_META, HEALTH_FILL, HEALTHS } from '../integrations/health';
import { loadIntegrationData, toRows } from '../integrations/integration-data';

const DAY_MS = 24 * 60 * 60 * 1000;
/** Stack and legend order: outcomes first, then work in flight, then failures (keeps adjacent hues apart). */
const STATUS_ORDER: OperationStatus[] = ['SUCCEEDED', 'RUNNING', 'RETRY', 'PENDING', 'FAILED', 'DEAD'];
const STATUS_FILL: Record<OperationStatus, string> = {
  SUCCEEDED: 'var(--chart-ok)',
  RUNNING: 'var(--chart-info)',
  RETRY: 'var(--chart-warn)',
  PENDING: 'var(--chart-neutral)',
  FAILED: 'var(--chart-serious)',
  DEAD: 'var(--chart-danger)',
};

@Component({
  selector: 'app-overview-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe, RouterLink, Icon, DonutChart, BarList],
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
  /** Hourly series; without a tenant the API already adds up every tenant. */
  protected readonly series = rxResource({
    params: () => ({ tenantId: this.scope.tenantId() }),
    stream: () => this.operationsApi.timeseries(new Date(Date.now() - DAY_MS).toISOString()),
  });
  /** Viewers only see the dashboard and operations (spec §5.2), so they skip the integrations call. */
  protected readonly showIntegrations = computed(() => {
    const role = this.auth.role();
    return role !== null && SECTION_ROLES.integrations.includes(role);
  });
  private readonly loadIntegrations = loadIntegrationData();
  protected readonly integrations = rxResource({
    params: () => (this.showIntegrations() ? { tenantId: this.scope.tenantId() } : undefined),
    stream: () => this.loadIntegrations(),
  });

  protected readonly totals = computed(() => {
    if (!this.summary.hasValue()) {
      return null;
    }
    const by = this.summary.value().byStatus;
    const count = (status: OperationStatus) => by[status] ?? 0;
    const total = STATUS_ORDER.reduce((sum, status) => sum + count(status), 0);
    const finished = count('SUCCEEDED') + count('FAILED') + count('DEAD');
    return {
      total,
      dead: this.summary.value().dead,
      inFlight: count('PENDING') + count('RUNNING') + count('RETRY'),
      retrying: count('RETRY'),
      successRate: finished ? Math.round((count('SUCCEEDED') / finished) * 1000) / 10 : null,
    };
  });

  protected readonly statusSegments = computed<DonutSegment[]>(() => {
    const by = this.summary.hasValue() ? this.summary.value().byStatus : {};
    return STATUS_ORDER.map((status) => ({
      key: status,
      ...STATUS_META[status],
      value: by[status] ?? 0,
      fill: STATUS_FILL[status],
      link: '/operaciones',
      queryParams: { status },
    }));
  });

  protected readonly flowBars = computed<BarItem[]>(() => {
    const by = this.summary.hasValue() ? this.summary.value().byFlow : {};
    return (Object.keys(FLOW_META) as Flow[]).map((flow) => ({
      key: flow,
      label: FLOW_META[flow].label,
      icon: FLOW_META[flow].icon,
      value: by[flow] ?? 0,
      link: '/operaciones',
      queryParams: { flow },
    }));
  });

  /** Columns of the activity chart, stacked by status, scaled to the busiest hour. */
  protected readonly activity = computed(() => {
    const points = this.series.hasValue() ? this.series.value() : [];
    const totals = points.map((point) => STATUS_ORDER.reduce((sum, status) => sum + (point.byStatus[status] ?? 0), 0));
    const max = Math.max(0, ...totals);
    return {
      max,
      empty: max === 0,
      columns: points.map((point, index) => ({
        t: point.t,
        total: totals[index],
        height: max ? (totals[index] / max) * 100 : 0,
        stack: STATUS_ORDER.filter((status) => point.byStatus[status]).map((status) => ({
          status,
          label: STATUS_META[status].label,
          value: point.byStatus[status] ?? 0,
          fill: STATUS_FILL[status],
          share: totals[index] ? ((point.byStatus[status] ?? 0) / totals[index]) * 100 : 0,
        })),
      })),
    };
  });
  protected readonly legend = STATUS_ORDER.map((status) => ({ status, label: STATUS_META[status].label, fill: STATUS_FILL[status] }));

  protected readonly integrationRows = computed(() => (this.integrations.hasValue() ? toRows(this.integrations.value()) : []));
  protected readonly healthSegments = computed<DonutSegment[]>(() =>
    HEALTHS.map((health) => ({
      key: health,
      ...HEALTH_META[health],
      value: this.integrationRows().filter((row) => row.health === health).length,
      fill: HEALTH_FILL[health],
    })),
  );
  protected readonly failing = computed(() => this.integrationRows().filter((row) => row.health === 'DEGRADED'));

  protected readonly flowMeta = FLOW_META;

  protected reload(): void {
    this.summary.reload();
    this.series.reload();
    this.integrations.reload();
  }

  protected columnLabel(column: { t: string; total: number; stack: { label: string; value: number }[] }, hour: string): string {
    return `${hour}: ${column.total} operaciones${column.stack.length ? ', ' + column.stack.map((s) => `${s.value} ${s.label.toLowerCase()}`).join(', ') : ''}`;
  }

  protected unavailable(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar.');
  }
}
