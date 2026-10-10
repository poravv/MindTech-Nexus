import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { Integration, IntegrationsApi } from '../../api/integrations.api';
import { Operation, OperationsApi } from '../../api/operations.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { allowedIntegrationStates, canManageTenantConfig, canRunCatalogSync, IntegrationState } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { Icon } from '../../shared/ui/icon';
import { STATUS_META, StatusBadge } from '../../shared/ui/status-badge';
import { MapNode } from '../../shared/ui/tree-layout';
import { TreeMap } from '../../shared/ui/tree-map';
import { CONNECTOR_META, FLOW_META } from './health';
import { loadIntegrationData, toRows } from './integration-data';
import { IntegrationFormDialog, POLICY_FIELDS } from './integration-form-dialog';
import { connectionNode, connectionSignals, healthSignal, operationNode, statusSignal } from './integration-map';
import { IntegrationStateDialog } from './state-dialog';

const DAY_MS = 24 * 60 * 60 * 1000;
/** Largest page the admin API returns (OperationQueries.MAX_PAGE_SIZE). */
const OPERATION_SAMPLE = 200;
const RECENT_NODES = 4;

/** One integration in the style of an Argo CD application: big status header, actions, and its resource tree. */
@Component({
  selector: 'app-integration-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink, Icon, StatusBadge, TreeMap, IntegrationStateDialog, IntegrationFormDialog],
  templateUrl: './integration-detail-page.html',
  styleUrl: './integration-detail-page.scss',
})
export default class IntegrationDetailPage {
  private readonly api = inject(IntegrationsApi);
  private readonly operationsApi = inject(OperationsApi);
  private readonly auth = inject(AuthService);
  private readonly notifier = inject(Notifier);
  protected readonly scope = inject(TenantScope);

  readonly id = input.required<string>();

  private readonly load = loadIntegrationData();
  protected readonly data = rxResource({
    params: () => ({ tenantId: this.scope.tenantId() }),
    stream: () => this.load(),
  });
  protected readonly row = computed(() =>
    this.data.hasValue() ? toRows(this.data.value()).find((row) => row.integration.id === this.id()) : undefined,
  );

  /** The admin API cannot filter by integration: take the flow's last 24 h and keep this integration's. */
  protected readonly recent = rxResource({
    params: () => {
      const integration = this.row()?.integration;
      return integration ? { id: integration.id, flow: integration.flow, tenantId: integration.tenantId } : undefined;
    },
    stream: ({ params }) =>
      this.operationsApi
        .list({ flow: params.flow, from: new Date(Date.now() - DAY_MS).toISOString(), page: 0, size: OPERATION_SAMPLE }, params.tenantId)
        .pipe(
          map((ops) => ops.filter((op) => op.integrationId === params.id)),
          catchError(() => of([] as Operation[])),
        ),
    defaultValue: [],
  });

  protected readonly tree = computed<MapNode | null>(() => {
    const row = this.row();
    if (!row) {
      return null;
    }
    const { integration } = row;
    const operations = this.recent.value().slice(0, RECENT_NODES);
    return {
      id: integration.id,
      icon: FLOW_META[integration.flow].icon,
      kind: FLOW_META[integration.flow].label.toLowerCase(),
      title: integration.code,
      signals: [healthSignal(row.health), statusSignal(integration.state)],
      note: `${FLOW_META[integration.flow].route} · v${integration.version}`,
      since: integration.updatedAt,
      children: [
        connectionNode(`${integration.id}:source`, row.source, 'origen'),
        connectionNode(`${integration.id}:target`, row.target, 'destino'),
        ...operations.map(operationNode),
      ],
    };
  });

  /** Why the health is what it is, in the operator's words. */
  protected readonly healthReason = computed(() => {
    const row = this.row();
    if (!row) {
      return '';
    }
    const by = row.insight?.byStatus ?? {};
    const total = Object.values(by).reduce((sum, value) => sum + (value ?? 0), 0);
    switch (row.health) {
      case 'DEGRADED':
        return [row.insight?.dead && `${row.insight.dead} en DLQ`, by.FAILED && `${by.FAILED} fallidas`].filter(Boolean).join(' · ') + ' en 24 h';
      case 'PROGRESSING':
        return row.integration.state === 'DRAINING' ? 'Terminando las operaciones en curso' : 'Hay operaciones en curso o reintentando';
      case 'HEALTHY':
        return `${total} ${total === 1 ? 'operación exitosa' : 'operaciones exitosas'} en 24 h`;
      case 'SUSPENDED':
        return 'La integración no está despachando';
      default:
        return 'Sin operaciones en las últimas 24 h';
    }
  });
  protected readonly stateHelp = computed(() => {
    const state = this.row()?.integration.state;
    return state === 'ENABLED' ? 'Despachando operaciones' : state === 'DRAINING' ? 'Terminando lo que está en curso' : 'No despacha operaciones';
  });

  protected readonly canChangeState = computed(() => {
    const role = this.auth.role();
    return role !== null && allowedIntegrationStates(role).length > 0;
  });
  protected readonly canRun = computed(() => {
    const role = this.auth.role();
    return role !== null && canRunCatalogSync(role);
  });
  protected readonly canManage = computed(() => {
    const role = this.auth.role();
    return role !== null && canManageTenantConfig(role);
  });

  protected readonly stateTarget = signal<Integration | null>(null);
  protected readonly suggestedState = signal<IntegrationState | null>(null);
  protected readonly formTarget = signal<Integration | null>(null);
  protected readonly showDetails = signal(false);

  protected readonly flowMeta = FLOW_META;
  protected readonly connectorMeta = CONNECTOR_META;
  protected readonly statusMeta = STATUS_META;
  protected readonly policyFields = [...POLICY_FIELDS, { key: 'scheduleEverySeconds' as const, label: 'Sincronizar cada (s)' }];
  protected readonly connectionSignals = connectionSignals;

  protected openState(integration: Integration, suggested: IntegrationState | null = null): void {
    this.suggestedState.set(suggested);
    this.stateTarget.set(integration);
  }

  protected refresh(): void {
    this.data.reload();
    this.recent.reload();
  }

  protected stateChanged(): void {
    this.stateTarget.set(null);
    this.refresh();
  }

  protected saved(): void {
    this.formTarget.set(null);
    this.refresh();
  }

  protected run(integration: Integration): void {
    this.api.run(integration).subscribe({
      next: () => this.notifier.success(`Sincronización de ${integration.code} encolada.`),
      error: (error) => this.notifier.error(problemMessage(error)),
    });
  }

  protected host(url: string): string {
    try {
      return new URL(url).host;
    } catch {
      return url;
    }
  }

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la integración.');
  }
}
