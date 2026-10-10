import { DatePipe } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnDestroy,
  signal,
  TemplateRef,
  viewChild,
  WritableSignal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Flow, Integration, IntegrationHealthStatus, IntegrationsApi } from '../../api/integrations.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { allowedIntegrationStates, canManageTenantConfig, canRunCatalogSync, IntegrationState } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { BarList } from '../../shared/ui/bar-list';
import { DonutChart, DonutSegment } from '../../shared/ui/donut-chart';
import { Icon, IconName } from '../../shared/ui/icon';
import { relativeTime } from '../../shared/ui/relative-time';
import { HEALTH_META, STATUS_META, StatusBadge } from '../../shared/ui/status-badge';
import { MapAction, MapNode } from '../../shared/ui/tree-layout';
import { TreeMap } from '../../shared/ui/tree-map';
import { RailSlot } from '../shell/rail-slot';
import { CONNECTOR_META, FLOW_META, HEALTH_FILL, HEALTHS } from './health';
import { IntegrationRow, loadIntegrationData, toRows } from './integration-data';
import { IntegrationFormDialog } from './integration-form-dialog';
import { integrationNode, ownTenantNode, platformNode, tenantNode } from './integration-map';
import { IntegrationStateDialog } from './state-dialog';

type View = 'tree' | 'tiles' | 'list' | 'charts';

const VIEWS: { id: View; label: string; icon: IconName }[] = [
  { id: 'tree', label: 'Mapa', icon: 'network' },
  { id: 'tiles', label: 'Mosaico', icon: 'layout-grid' },
  { id: 'list', label: 'Lista', icon: 'list' },
  { id: 'charts', label: 'Gráficos', icon: 'chart-pie' },
];
const STATES: IntegrationState[] = ['ENABLED', 'PAUSED', 'DRAINING', 'DISABLED', 'EMERGENCY_STOP'];
const VIEW_KEY = 'nexus.integrations-view.v2';
/** Failing integrations first: the operator opens this page to find what is broken. */
const HEALTH_ORDER = Object.fromEntries(HEALTHS.map((health, index) => [health, index])) as Record<IntegrationHealthStatus, number>;

@Component({
  selector: 'app-integrations-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule, RouterLink, Icon, StatusBadge, TreeMap, DonutChart, BarList, IntegrationStateDialog, IntegrationFormDialog],
  templateUrl: './integrations-page.html',
  styleUrl: './integrations-page.scss',
  host: { '(document:keydown)': 'focusSearch($event)' },
})
export default class IntegrationsPage implements OnDestroy {
  private readonly api = inject(IntegrationsApi);
  private readonly notifier = inject(Notifier);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly railSlot = inject(RailSlot);
  protected readonly scope = inject(TenantScope);
  private readonly facets = viewChild.required<TemplateRef<unknown>>('facets');

  constructor() {
    afterNextRender(() => this.railSlot.content.set(this.facets()));
  }

  private readonly load = loadIntegrationData();
  protected readonly data = rxResource({
    params: () => ({ tenantId: this.scope.tenantId() }),
    stream: () => this.load(),
  });
  protected readonly allTenants = computed(() => this.scope.needsTenant());
  protected readonly tenants = computed(() => (this.data.hasValue() ? this.data.value().tenants : []));

  protected readonly rows = computed(() =>
    this.data.hasValue()
      ? toRows(this.data.value()).sort(
          (a, b) => HEALTH_ORDER[a.health] - HEALTH_ORDER[b.health] || a.integration.code.localeCompare(b.integration.code),
        )
      : [],
  );

  protected readonly query = signal('');
  protected readonly stateFilter = signal(new Set<IntegrationState>());
  protected readonly healthFilter = signal(new Set<IntegrationHealthStatus>());
  protected readonly flowFilter = signal(new Set<Flow>());
  protected readonly tenantFilter = signal(new Set<string>());
  protected readonly view = signal<View>(readView());

  protected readonly visible = computed(() => {
    const query = this.query().trim().toLowerCase();
    const states = this.stateFilter();
    const healths = this.healthFilter();
    const flows = this.flowFilter();
    const tenants = this.tenantFilter();
    return this.rows().filter(
      (row) =>
        (!query ||
          [row.integration.code, row.source?.name, row.target?.name, row.tenant?.name].some((text) => text?.toLowerCase().includes(query))) &&
        (!states.size || states.has(row.integration.state)) &&
        (!healths.size || healths.has(row.health)) &&
        (!flows.size || flows.has(row.integration.flow)) &&
        (!tenants.size || tenants.has(row.integration.tenantId)),
    );
  });
  protected readonly isFiltered = computed(
    () =>
      !!this.query().trim() || !!this.stateFilter().size || !!this.healthFilter().size || !!this.flowFilter().size || !!this.tenantFilter().size,
  );
  /** Facet counters over the whole list (not the filtered one), as in Argo CD. */
  protected readonly counts = computed(() => {
    const counts = new Map<string, number>();
    for (const row of this.rows()) {
      for (const key of [row.integration.state, row.health, row.integration.flow, row.integration.tenantId]) {
        counts.set(key, (counts.get(key) ?? 0) + 1);
      }
    }
    return counts;
  });
  protected readonly totalDead = computed(() => this.rows().reduce((sum, row) => sum + (row.insight?.dead ?? 0), 0));

  protected readonly canManage = computed(() => {
    const role = this.auth.role();
    return role !== null && canManageTenantConfig(role);
  });
  protected readonly canRun = computed(() => {
    const role = this.auth.role();
    return role !== null && canRunCatalogSync(role);
  });
  protected readonly canChangeState = computed(() => {
    const role = this.auth.role();
    return role !== null && allowedIntegrationStates(role).length > 0;
  });

  // ---- Map ----

  protected readonly tree = computed<MapNode>(() => {
    const rows = this.visible();
    const branch = (own: IntegrationRow[]) => own.map((row) => integrationNode(row, this.actionsFor(row.integration)));
    if (!this.scope.isPlatformAdmin()) {
      return ownTenantNode(rows, branch(rows));
    }
    const tenants = this.tenants().filter((tenant) => !this.isFiltered() || rows.some((row) => row.integration.tenantId === tenant.id));
    const tenantBranch = (tenant: (typeof tenants)[number]) => {
      const own = rows.filter((row) => row.integration.tenantId === tenant.id);
      return tenantNode(tenant, own, branch(own));
    };
    if (!this.allTenants() && tenants.length === 1) {
      return tenantBranch(tenants[0]);
    }
    return platformNode(
      tenants,
      rows,
      tenants.map((tenant) => {
        const node = tenantBranch(tenant);
        return { ...node, actions: [{ id: 'focus', label: 'Ver solo este tenant', icon: 'search' as const }] };
      }),
    );
  });
  /** With every tenant on screen, integrations start folded (their connections one click away). */
  protected readonly folded = computed(() => (this.allTenants() ? this.visible().map((row) => row.integration.id) : []));

  private actionsFor(integration: Integration): MapAction[] {
    const actions: MapAction[] = [{ id: 'open', label: 'Ver detalle', icon: 'arrow-right' }];
    if (this.canChangeState()) {
      actions.push({ id: 'state', label: 'Cambiar estado', icon: 'arrow-right-left' });
    }
    if (this.canRun() && integration.flow === 'CATALOG') {
      actions.push({ id: 'run', label: 'Sincronizar ahora', icon: 'play' });
    }
    if (this.canManage()) {
      actions.push({ id: 'edit', label: 'Editar', icon: 'pencil' });
    }
    actions.push({ id: 'ops', label: 'Ver operaciones', icon: 'history' });
    return actions;
  }

  protected onMapAction({ node, action }: { node: MapNode; action: MapAction }): void {
    if (action.id === 'focus') {
      this.scope.select(node.id);
      return;
    }
    const integration = this.rows().find((row) => row.integration.id === node.id)?.integration;
    if (!integration) {
      return;
    }
    switch (action.id) {
      case 'open':
        void this.router.navigate(['/integraciones', integration.id]);
        break;
      case 'state':
        this.stateTarget.set(integration);
        break;
      case 'run':
        this.run(integration);
        break;
      case 'edit':
        this.formTarget.set(integration);
        break;
      case 'ops':
        void this.router.navigate(['/operaciones'], { queryParams: { flow: integration.flow } });
        break;
    }
  }

  // ---- Charts ----

  protected readonly healthSegments = computed<DonutSegment[]>(() =>
    HEALTHS.map((health) => ({
      key: health,
      ...HEALTH_META[health],
      value: this.visible().filter((row) => row.health === health).length,
      fill: HEALTH_FILL[health],
    })),
  );
  protected readonly flowBars = computed(() =>
    (Object.keys(FLOW_META) as Flow[]).map((flow) => ({
      key: flow,
      label: FLOW_META[flow].label,
      icon: FLOW_META[flow].icon,
      value: this.visible().filter((row) => row.integration.flow === flow).length,
    })),
  );
  protected readonly stateBars = computed(() =>
    STATES.map((state) => ({
      key: state,
      label: STATUS_META[state].label,
      icon: STATUS_META[state].icon,
      value: this.visible().filter((row) => row.integration.state === state).length,
    })),
  );
  protected readonly tenantBars = computed(() =>
    this.tenants().map((tenant) => ({
      key: tenant.id,
      label: tenant.name,
      icon: 'building' as const,
      value: this.visible().filter((row) => row.integration.tenantId === tenant.id).length,
    })),
  );
  protected readonly dlqBars = computed(() =>
    this.visible()
      .filter((row) => row.insight?.dead)
      .map((row) => ({
        key: row.integration.id,
        label: row.integration.code,
        icon: FLOW_META[row.integration.flow].icon,
        value: row.insight?.dead ?? 0,
        link: `/integraciones/${row.integration.id}`,
      }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 8),
  );

  // ---- Options and dialogs ----

  protected readonly views = VIEWS;
  protected readonly flowMeta = FLOW_META;
  protected readonly connectorMeta = CONNECTOR_META;
  protected readonly stateOptions = STATES;
  protected readonly healthOptions = HEALTHS;
  protected readonly flowTypes = Object.keys(FLOW_META) as Flow[];

  protected readonly formTarget = signal<Integration | 'new' | null>(null);
  protected readonly stateTarget = signal<Integration | null>(null);

  protected age(iso: string): string {
    return relativeTime(iso);
  }

  protected saved(): void {
    this.formTarget.set(null);
    this.data.reload();
  }

  protected stateChanged(): void {
    this.stateTarget.set(null);
    this.data.reload();
  }

  protected toggle<T>(filter: WritableSignal<Set<T>>, value: T): void {
    filter.update((current) => {
      const next = new Set(current);
      if (!next.delete(value)) {
        next.add(value);
      }
      return next;
    });
  }

  protected clearFilters(): void {
    this.query.set('');
    this.stateFilter.set(new Set());
    this.healthFilter.set(new Set());
    this.flowFilter.set(new Set());
    this.tenantFilter.set(new Set());
  }

  protected setView(view: View): void {
    this.view.set(view);
    try {
      localStorage.setItem(VIEW_KEY, view);
    } catch {
      // Without storage the choice lasts until reload.
    }
  }

  /** "/" jumps to the name filter, as in Argo CD and GitHub. */
  protected focusSearch(event: KeyboardEvent): void {
    const target = event.target as HTMLElement | null;
    if (event.key !== '/' || target?.closest('input, textarea, select, [contenteditable]')) {
      return;
    }
    event.preventDefault();
    document.getElementById('int-search')?.focus();
  }

  ngOnDestroy(): void {
    this.railSlot.content.set(null);
  }

  protected run(integration: Integration): void {
    this.api.run(integration).subscribe({
      next: () => this.notifier.success(`Sincronización de ${integration.code} encolada.`),
      error: (error) => this.notifier.error(problemMessage(error)),
    });
  }

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la lista.');
  }
}

function readView(): View {
  try {
    const stored = localStorage.getItem(VIEW_KEY);
    return VIEWS.some((view) => view.id === stored) ? (stored as View) : 'tree';
  } catch {
    return 'tree';
  }
}
