import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { ConnectionsApi, Connector } from '../../api/connections.api';
import { Flow, Integration, IntegrationDraft, IntegrationPolicy, IntegrationsApi } from '../../api/integrations.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { allowedIntegrationStates, canManageTenantConfig, canRunCatalogSync, IntegrationState } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { Modal } from '../../shared/ui/modal';
import { StatusBadge } from '../../shared/ui/status-badge';
import { TenantRequired } from '../../shared/ui/tenant-required';

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

const STATE_HELP: Record<IntegrationState, string> = {
  ENABLED: 'Procesa operaciones normalmente.',
  PAUSED: 'Retiene las operaciones sin consumir intentos; se reanudan al habilitar.',
  DRAINING: 'Termina las operaciones en curso y rechaza nuevas.',
  DISABLED: 'No despacha ni acepta operaciones nuevas.',
  EMERGENCY_STOP: 'Corta todo de inmediato. Solo administrador de plataforma.',
};

@Component({
  selector: 'app-integrations-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Modal, StatusBadge, TenantRequired],
  templateUrl: './integrations-page.html',
})
export default class IntegrationsPage {
  private readonly api = inject(IntegrationsApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly notifier = inject(Notifier);
  private readonly auth = inject(AuthService);
  protected readonly scope = inject(TenantScope);

  protected readonly data = rxResource({
    params: () => (this.scope.needsTenant() ? undefined : { tenantId: this.scope.tenantId() }),
    stream: () => forkJoin({ integrations: this.api.list(), connections: this.connectionsApi.list() }),
  });
  protected readonly connectionNames = computed(() =>
    this.data.hasValue() ? new Map(this.data.value().connections.map((c) => [c.id, c.name])) : new Map<string, string>(),
  );

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
  protected readonly stateHelp = STATE_HELP;

  protected readonly editing = signal<{ id?: string } | null>(null);
  protected form: IntegrationDraft = emptyDraft();
  protected readonly stateTarget = signal<Integration | null>(null);
  protected nextState: IntegrationState = 'PAUSED';
  protected reason = '';
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

  protected openStateChange(integration: Integration): void {
    this.nextState = this.states().find((state) => state !== integration.state) ?? 'PAUSED';
    this.reason = '';
    this.formError.set(null);
    this.stateTarget.set(integration);
  }

  protected changeState(): void {
    const integration = this.stateTarget();
    if (!integration) {
      return;
    }
    this.busy.set(true);
    this.api.changeState(integration.id, this.nextState, this.reason.trim()).subscribe({
      next: () => {
        this.busy.set(false);
        this.stateTarget.set(null);
        this.notifier.success(`${integration.code}: estado cambiado.`);
        this.data.reload();
      },
      error: (error) => {
        this.busy.set(false);
        this.formError.set(problemMessage(error));
      },
    });
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

function emptyDraft(): IntegrationDraft {
  return { flow: 'FISCAL', code: '', sourceConnectionId: '', targetConnectionId: '', policy: { ...DEFAULT_POLICY } };
}

