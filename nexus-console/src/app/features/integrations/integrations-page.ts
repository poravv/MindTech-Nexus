import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnDestroy,
  signal,
  TemplateRef,
  viewChild,
  afterNextRender,
  WritableSignal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ConnectionsApi, Connector } from '../../api/connections.api';
import { Flow, Integration, IntegrationDraft, IntegrationPolicy, IntegrationsApi } from '../../api/integrations.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { allowedIntegrationStates, canManageTenantConfig, canRunCatalogSync, IntegrationState } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { Icon } from '../../shared/ui/icon';
import { Modal } from '../../shared/ui/modal';
import { STATUS_META, StatusBadge } from '../../shared/ui/status-badge';
import { TenantRequired } from '../../shared/ui/tenant-required';
import { RailSlot } from '../shell/rail-slot';
import { CONNECTOR_ICONS, FLOW_META, Health, HEALTHS } from './health';
import { loadIntegrationData, toRows } from './integration-data';
import { IntegrationStateDialog } from './state-dialog';

const STATES: IntegrationState[] = ['ENABLED', 'PAUSED', 'DRAINING', 'DISABLED', 'EMERGENCY_STOP'];
/** Failing integrations first: the operator opens this page to find what is broken. */
const HEALTH_ORDER: Record<Health, number> = { DEGRADED: 0, PROGRESSING: 1, UNKNOWN: 2, IDLE: 3, HEALTHY: 4 };
const VIEW_KEY = 'nexus.integrations-view';


/** Source → target connector of each flow (spec §1). */
const FLOWS: Record<Flow, { label: string; source: Connector; target: Connector }> = {
  FISCAL: { label: 'Fiscal (POS → SIFEN)', source: 'POS', target: 'SIFEN' },
  ORDER: { label: 'Pedidos (GiftHub → POS)', source: 'GIFTHUB', target: 'POS' },
  CATALOG: { label: 'Catálogo (POS → GiftHub)', source: 'POS', target: 'GIFTHUB' },
};

const DEFAULT_POLICY: IntegrationPolicy = {
  ratePerSecond: 2,
  burst: 5,
  maxInFlight: 2,
  maxAttempts: 5,
  baseDelaySeconds: 5,
  maxDelaySeconds: 300,
  readTimeoutMs: 150000,
  scheduleEverySeconds: 300,
};

const POLICY_FIELDS: { key: keyof IntegrationPolicy; label: string }[] = [
  { key: 'ratePerSecond', label: 'Operaciones por segundo' },
  { key: 'burst', label: 'Ráfaga' },
  { key: 'maxInFlight', label: 'Máx. en curso' },
  { key: 'maxAttempts', label: 'Máx. intentos' },
  { key: 'baseDelaySeconds', label: 'Espera base (s)' },
  { key: 'maxDelaySeconds', label: 'Espera máxima (s)' },
  { key: 'readTimeoutMs', label: 'Timeout de lectura (ms)' },
];

@Component({
  selector: 'app-integrations-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule, RouterLink, Icon, Modal, StatusBadge, TenantRequired, IntegrationStateDialog],
  templateUrl: './integrations-page.html',
  styleUrl: './integrations-page.scss',
  host: { '(document:keydown)': 'focusSearch($event)' },
})
export default class IntegrationsPage implements OnDestroy {
  private readonly api = inject(IntegrationsApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly notifier = inject(Notifier);
  private readonly auth = inject(AuthService);
  private readonly railSlot = inject(RailSlot);
  protected readonly scope = inject(TenantScope);
  private readonly facets = viewChild.required<TemplateRef<unknown>>('facets');

  constructor() {
    afterNextRender(() => this.railSlot.content.set(this.facets()));
  }

  private readonly load = loadIntegrationData();
  protected readonly data = rxResource({
    params: () => (this.scope.needsTenant() ? undefined : { tenantId: this.scope.tenantId() }),
    stream: () => this.load(),
  });

  protected readonly rows = computed(() =>
    this.data.hasValue()
      ? toRows(this.data.value()).sort(
          (a, b) => HEALTH_ORDER[a.health] - HEALTH_ORDER[b.health] || a.integration.code.localeCompare(b.integration.code),
        )
      : [],
  );

  protected readonly query = signal('');
  protected readonly stateFilter = signal(new Set<IntegrationState>());
  protected readonly healthFilter = signal(new Set<Health>());
  protected readonly flowFilter = signal(new Set<Flow>());
  protected readonly view = signal<'list' | 'tiles'>(readView());

  protected readonly visible = computed(() => {
    const query = this.query().trim().toLowerCase();
    const states = this.stateFilter();
    const healths = this.healthFilter();
    const flows = this.flowFilter();
    return this.rows().filter(
      (row) =>
        (!query ||
          [row.integration.code, row.source?.name, row.target?.name].some((text) => text?.toLowerCase().includes(query))) &&
        (!states.size || states.has(row.integration.state)) &&
        (!healths.size || healths.has(row.health)) &&
        (!flows.size || flows.has(row.integration.flow)),
    );
  });
  protected readonly isFiltered = computed(
    () => !!this.query().trim() || !!this.stateFilter().size || !!this.healthFilter().size || !!this.flowFilter().size,
  );
  /** Facet counters over the whole list (not the filtered one), as in Argo CD. */
  protected readonly counts = computed(() => {
    const counts = new Map<string, number>();
    for (const row of this.rows()) {
      for (const key of [row.integration.state, row.health, row.integration.flow]) {
        counts.set(key, (counts.get(key) ?? 0) + 1);
      }
    }
    return counts;
  });
  protected readonly healthBar = computed(() => {
    const total = this.visible().length;
    return HEALTHS.map((health) => {
      const count = this.visible().filter((row) => row.health === health).length;
      return { health, count, share: total ? (count / total) * 100 : 0 };
    }).filter((segment) => segment.count > 0);
  });
  protected readonly healthBarLabel = computed(() =>
    this.healthBar()
      .map((segment) => `${segment.count} ${this.healthLabel(segment.health)}`)
      .join(', '),
  );
  protected readonly totalDead = computed(() => this.rows().reduce((sum, row) => sum + row.dead, 0));

  protected readonly canManage = computed(() => {
    const role = this.auth.role();
    return role !== null && canManageTenantConfig(role);
  });
  protected readonly canRun = computed(() => {
    const role = this.auth.role();
    return role !== null && canRunCatalogSync(role);
  });
  protected readonly states = computed(() => {
    const role = this.auth.role();
    return role ? allowedIntegrationStates(role) : [];
  });

  protected readonly flows = FLOWS;
  protected readonly flowTypes = Object.keys(FLOWS) as Flow[];
  protected readonly policyFields = POLICY_FIELDS;
  protected readonly flowMeta = FLOW_META;
  protected readonly connectorIcons = CONNECTOR_ICONS;
  protected readonly stateOptions = STATES;
  protected readonly healthOptions = HEALTHS;

  protected readonly editing = signal<{ id?: string } | null>(null);
  protected form: IntegrationDraft = emptyDraft();
  protected readonly stateTarget = signal<Integration | null>(null);
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected connectionsFor(connector: Connector) {
    return this.data.hasValue() ? this.data.value().connections.filter((c) => c.connector === connector) : [];
  }

  protected openCreate(): void {
    this.form = emptyDraft();
    this.formError.set(null);
    this.editing.set({});
  }

  protected openEdit(integration: Integration): void {
    this.form = {
      flow: integration.flow,
      code: integration.code,
      sourceConnectionId: integration.sourceConnectionId,
      targetConnectionId: integration.targetConnectionId,
      policy: { ...DEFAULT_POLICY, ...integration.policy },
    };
    this.formError.set(null);
    this.editing.set({ id: integration.id });
  }

  protected changeFlow(flow: Flow): void {
    this.form = { ...this.form, flow, sourceConnectionId: '', targetConnectionId: '' };
  }

  protected save(): void {
    const target = this.editing();
    if (!target) {
      return;
    }
    const policy = { ...this.form.policy };
    if (this.form.flow !== 'CATALOG') {
      delete policy.scheduleEverySeconds;
    }
    const draft = { ...this.form, code: this.form.code.trim(), policy };
    this.busy.set(true);
    const request = target.id ? this.api.update(target.id, draft) : this.api.create(draft);
    request.subscribe({
      next: (integration) => {
        this.busy.set(false);
        this.editing.set(null);
        this.notifier.success(`Integración ${integration.code} guardada.`);
        this.data.reload();
      },
      error: (error) => {
        this.busy.set(false);
        this.formError.set(problemMessage(error));
      },
    });
  }

  protected healthLabel(health: Health): string {
    return STATUS_META[health].label.toLowerCase();
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
  }

  protected setView(view: 'list' | 'tiles'): void {
    this.view.set(view);
    try {
      localStorage.setItem(VIEW_KEY, view);
    } catch {
      // Without storage the choice lasts until reload.
    }
  }

  /** "/" jumps to the search box, as in Argo CD and GitHub. */
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
    this.api.run(integration.id).subscribe({
      next: () => this.notifier.success(`Sincronización de ${integration.code} encolada.`),
      error: (error) => this.notifier.error(problemMessage(error)),
    });
  }

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la lista.');
  }
}

function readView(): 'list' | 'tiles' {
  try {
    return localStorage.getItem(VIEW_KEY) === 'tiles' ? 'tiles' : 'list';
  } catch {
    return 'list';
  }
}

function emptyDraft(): IntegrationDraft {
  return { flow: 'FISCAL', code: '', sourceConnectionId: '', targetConnectionId: '', policy: { ...DEFAULT_POLICY } };
}

