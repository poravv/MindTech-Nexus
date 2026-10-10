import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Connection, Connector } from '../../api/connections.api';
import { Flow, Integration, IntegrationDraft, IntegrationPolicy, IntegrationsApi } from '../../api/integrations.api';
import { Tenant } from '../../api/tenants.api';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { TenantScope } from '../../core/tenant-scope.service';
import { Modal } from '../../shared/ui/modal';

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

export const POLICY_FIELDS: { key: keyof IntegrationPolicy; label: string }[] = [
  { key: 'ratePerSecond', label: 'Operaciones por segundo' },
  { key: 'burst', label: 'Ráfaga' },
  { key: 'maxInFlight', label: 'Máx. en curso' },
  { key: 'maxAttempts', label: 'Máx. intentos' },
  { key: 'baseDelaySeconds', label: 'Espera base (s)' },
  { key: 'maxDelaySeconds', label: 'Espera máxima (s)' },
  { key: 'readTimeoutMs', label: 'Timeout de lectura (ms)' },
];

/**
 * Creates (`target` = 'new') or edits an integration. On "Todos los tenants" a new integration first asks for its
 * tenant; connections are always offered from the integration's own tenant.
 */
@Component({
  selector: 'app-integration-form-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Modal],
  template: `
    <app-modal [heading]="editing() ? 'Editar integración' : 'Nueva integración'" [open]="target() !== null" (closed)="closed.emit()">
      <form class="form" (ngSubmit)="save()" #ngForm="ngForm">
        @if (error(); as message) {
          <p class="alert" role="alert">{{ message }}</p>
        }
        <div class="form-grid">
          @if (asksTenant()) {
            <div class="field">
              <label for="int-tenant">Tenant</label>
              <select id="int-tenant" name="tenant" class="select" required [ngModel]="tenantId()" (ngModelChange)="changeTenant($event)">
                <option value="" disabled>Elegí un tenant</option>
                @for (tenant of tenants(); track tenant.id) {
                  <option [value]="tenant.id">{{ tenant.name }}</option>
                }
              </select>
            </div>
          }
          <div class="field">
            <label for="int-flow">Flujo</label>
            <select id="int-flow" name="flow" class="select" [ngModel]="form.flow" (ngModelChange)="changeFlow($event)" [disabled]="!!editing()">
              @for (flow of flowTypes; track flow) {
                <option [value]="flow">{{ flows[flow].label }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label for="int-code">Código</label>
            <input id="int-code" name="code" class="input mono" required maxlength="100" [(ngModel)]="form.code"
                   [readonly]="!!editing()" placeholder="fiscal-pos-centro" />
          </div>
          <div class="field">
            <label for="int-source">Origen ({{ flows[form.flow].source }})</label>
            <select id="int-source" name="source" class="select" required [(ngModel)]="form.sourceConnectionId">
              <option value="" disabled>Elegí una conexión</option>
              @for (c of connectionsFor(flows[form.flow].source); track c.id) {
                <option [value]="c.id">{{ c.name }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label for="int-target">Destino ({{ flows[form.flow].target }})</label>
            <select id="int-target" name="target" class="select" required [(ngModel)]="form.targetConnectionId">
              <option value="" disabled>Elegí una conexión</option>
              @for (c of connectionsFor(flows[form.flow].target); track c.id) {
                <option [value]="c.id">{{ c.name }}</option>
              }
            </select>
          </div>
        </div>

        <fieldset>
          <legend>Política</legend>
          <div class="form-grid">
            @for (field of policyFields; track field.key) {
              <div class="field">
                <label [for]="'pol-' + field.key">{{ field.label }}</label>
                <input [id]="'pol-' + field.key" [name]="'pol-' + field.key" type="number" min="1" required class="input"
                       [(ngModel)]="form.policy[field.key]" />
              </div>
            }
            @if (form.flow === 'CATALOG') {
              <div class="field">
                <label for="pol-schedule">Sincronizar cada (s)</label>
                <input id="pol-schedule" name="pol-schedule" type="number" min="60" required class="input"
                       [(ngModel)]="form.policy.scheduleEverySeconds" />
              </div>
            }
          </div>
        </fieldset>

        <div class="form-actions">
          <button type="button" class="btn" (click)="closed.emit()">Cancelar</button>
          <button type="submit" class="btn btn-primary" [disabled]="busy() || ngForm.invalid">Guardar</button>
        </div>
      </form>
    </app-modal>
  `,
})
export class IntegrationFormDialog {
  private readonly api = inject(IntegrationsApi);
  private readonly notifier = inject(Notifier);
  private readonly scope = inject(TenantScope);

  readonly target = input<Integration | 'new' | null>(null);
  readonly connections = input<Connection[]>([]);
  readonly tenants = input<Tenant[]>([]);
  readonly closed = output<void>();
  readonly saved = output<Integration>();

  protected readonly flows = FLOWS;
  protected readonly flowTypes = Object.keys(FLOWS) as Flow[];
  protected readonly policyFields = POLICY_FIELDS;
  protected readonly editing = computed(() => {
    const target = this.target();
    return target === 'new' ? null : target;
  });
  protected readonly asksTenant = computed(() => this.target() === 'new' && this.scope.needsTenant());
  protected readonly tenantId = signal('');
  protected form: IntegrationDraft = emptyDraft();
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const target = this.target();
      if (target === null) {
        return;
      }
      this.error.set(null);
      if (target === 'new') {
        this.form = emptyDraft();
        this.tenantId.set(this.scope.tenantId() ?? '');
      } else {
        this.form = {
          flow: target.flow,
          code: target.code,
          sourceConnectionId: target.sourceConnectionId,
          targetConnectionId: target.targetConnectionId,
          policy: { ...DEFAULT_POLICY, ...target.policy },
        };
        this.tenantId.set(target.tenantId);
      }
    });
  }

  protected connectionsFor(connector: Connector): Connection[] {
    const tenantId = this.tenantId();
    return this.connections().filter((c) => c.connector === connector && (!tenantId || c.tenantId === tenantId));
  }

  protected changeTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    this.form = { ...this.form, sourceConnectionId: '', targetConnectionId: '' };
  }

  protected changeFlow(flow: Flow): void {
    this.form = { ...this.form, flow, sourceConnectionId: '', targetConnectionId: '' };
  }

  protected save(): void {
    const policy = { ...this.form.policy };
    if (this.form.flow !== 'CATALOG') {
      delete policy.scheduleEverySeconds;
    }
    const draft = { ...this.form, code: this.form.code.trim(), policy };
    const editing = this.editing();
    this.busy.set(true);
    const request = editing ? this.api.update(editing, draft) : this.api.create(draft, this.tenantId() || null);
    request.subscribe({
      next: (integration) => {
        this.busy.set(false);
        this.notifier.success(`Integración ${integration.code} guardada.`);
        this.saved.emit(integration);
      },
      error: (error) => {
        this.busy.set(false);
        this.error.set(problemMessage(error));
      },
    });
  }
}

function emptyDraft(): IntegrationDraft {
  return { flow: 'FISCAL', code: '', sourceConnectionId: '', targetConnectionId: '', policy: { ...DEFAULT_POLICY } };
}
