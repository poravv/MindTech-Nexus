import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Connection, Connector } from '../../api/connections.api';
import { Flow, Integration, IntegrationDraft, IntegrationPolicy, IntegrationsApi } from '../../api/integrations.api';
import { Tenant } from '../../api/tenants.api';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { TenantScope } from '../../core/tenant-scope.service';
import { FormSection } from '../../shared/ui/form-section';
import { Icon, IconName } from '../../shared/ui/icon';
import { Modal } from '../../shared/ui/modal';

/** Source → target connector of each flow (spec §1). */
const FLOWS: Record<Flow, { label: string; note: string; icon: IconName; source: Connector; target: Connector }> = {
  FISCAL: { label: 'Fiscal', note: 'POS → SIFEN · factura las ventas', icon: 'receipt', source: 'POS', target: 'SIFEN' },
  ORDER: { label: 'Pedidos', note: 'GiftHub → POS · carga pedidos online', icon: 'shopping-bag', source: 'GIFTHUB', target: 'POS' },
  CATALOG: { label: 'Catálogo', note: 'POS → GiftHub · publica productos', icon: 'package', source: 'POS', target: 'GIFTHUB' },
};

const CONNECTOR_LABELS: Record<Connector, string> = { POS: 'Punto de venta', SIFEN: 'SIFEN', GIFTHUB: 'GiftHub' };

/** Same defaults and limits as the API's IntegrationPolicy: SIFEN can hold a FISCAL call ~183 s. */
export function defaultPolicy(flow: Flow): IntegrationPolicy {
  return {
    ratePerSecond: 2,
    burst: 5,
    maxInFlight: 2,
    maxAttempts: 5,
    baseDelaySeconds: 5,
    maxDelaySeconds: 300,
    readTimeoutMs: flow === 'FISCAL' ? 200000 : 150000,
    scheduleEverySeconds: 300,
  };
}

type PolicyGroup = 'Ritmo' | 'Reintentos' | 'Tiempo de espera';

export const POLICY_FIELDS: {
  key: keyof IntegrationPolicy;
  label: string;
  group: PolicyGroup;
  unit?: string;
  hint: string;
  min: number;
  max?: number;
}[] = [
  { key: 'ratePerSecond', label: 'Operaciones por segundo', group: 'Ritmo', unit: 'op/s', hint: 'Ritmo sostenido hacia el destino.', min: 1 },
  { key: 'burst', label: 'Ráfaga', group: 'Ritmo', unit: 'op', hint: 'Picos permitidos por encima del ritmo.', min: 1 },
  { key: 'maxInFlight', label: 'Máx. en curso', group: 'Ritmo', unit: 'op', hint: 'Llamadas simultáneas al destino.', min: 1 },
  { key: 'maxAttempts', label: 'Máx. intentos', group: 'Reintentos', hint: 'Al agotarlos la operación va a la DLQ.', min: 1, max: 100 },
  { key: 'baseDelaySeconds', label: 'Espera base', group: 'Reintentos', unit: 's', hint: 'Primer reintento; luego crece exponencial.', min: 1 },
  { key: 'maxDelaySeconds', label: 'Espera máxima', group: 'Reintentos', unit: 's', hint: 'Tope entre reintentos.', min: 1 },
  { key: 'readTimeoutMs', label: 'Timeout de lectura', group: 'Tiempo de espera', unit: 'ms', hint: 'Cuánto espera la respuesta del destino.', min: 1000, max: 280000 },
];

const POLICY_GROUPS: PolicyGroup[] = ['Ritmo', 'Reintentos', 'Tiempo de espera'];

/**
 * Creates (`target` = 'new') or edits an integration. On "Todos los tenants" a new integration first asks for its
 * tenant; connections are always offered from the integration's own tenant.
 */
@Component({
  selector: 'app-integration-form-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, FormSection, Icon, Modal],
  template: `
    <app-modal variant="sheet" [heading]="editing() ? 'Editar integración' : 'Nueva integración'"
               [description]="editing() ? 'Cambiá conexiones o política de ' + editing()!.code + '.' : 'Conecta un origen con un destino para un flujo.'"
               [open]="target() !== null" (closed)="closed.emit()">
      @if (target() !== null) {
      <form id="integration-form" class="form" (ngSubmit)="submit(ngForm.valid)" #ngForm="ngForm">
        <app-form-section heading="Flujo" [description]="editing() ? 'El flujo no se puede cambiar una vez creada la integración.' : ''">
          <div class="choice-grid" role="radiogroup" aria-label="Flujo">
            @for (flow of editing() ? [form.flow] : flowTypes; track flow) {
              <label class="choice">
                <input type="radio" name="flow" [value]="flow" [ngModel]="form.flow" (ngModelChange)="changeFlow($event)" />
                <span class="choice-icon"><app-icon [name]="flows[flow].icon" /></span>
                <span class="choice-text">
                  <span class="choice-title">{{ flows[flow].label }}</span>
                  <span class="choice-note">{{ flows[flow].note }}</span>
                </span>
                <app-icon class="choice-check" name="circle-check" />
              </label>
            }
          </div>
        </app-form-section>

        <app-form-section heading="Identificación" [columns]="asksTenant() ? 2 : 1">
          @if (asksTenant()) {
            <div class="field">
              <label for="int-tenant">Tenant<span class="req" aria-hidden="true">*</span></label>
              <select id="int-tenant" name="tenant" class="select" required [ngModel]="tenantId()" (ngModelChange)="changeTenant($event)"
                      #tenantModel="ngModel" [attr.aria-invalid]="tenantModel.invalid && (tenantModel.touched || ngForm.submitted)">
                <option value="" disabled>Elegí un tenant</option>
                @for (tenant of tenants(); track tenant.id) {
                  <option [value]="tenant.id">{{ tenant.name }}</option>
                }
              </select>
              @if (tenantModel.invalid && (tenantModel.touched || ngForm.submitted)) {
                <span class="field-error" role="alert">Elegí el tenant.</span>
              }
            </div>
          }
          <div class="field">
            <label for="int-code">Código<span class="req" aria-hidden="true">*</span></label>
            <input id="int-code" name="code" class="input mono" required maxlength="100" [(ngModel)]="form.code" #codeModel="ngModel"
                   [readonly]="!!editing()" placeholder="fiscal-pos-centro" spellcheck="false" autocomplete="off"
                   aria-describedby="int-code-hint" [attr.aria-invalid]="codeModel.invalid && (codeModel.touched || ngForm.submitted)" />
            @if (codeModel.invalid && (codeModel.touched || ngForm.submitted)) {
              <span class="field-error" role="alert">Ingresá un código.</span>
            }
            <span id="int-code-hint" class="hint">Identifica la integración en operaciones y logs. No se puede cambiar después.</span>
          </div>
        </app-form-section>

        <app-form-section heading="Conexiones" description="Desde dónde llegan las operaciones y hacia dónde van." [columns]="2">
          <div class="field">
            <label for="int-source">Origen · {{ connectorLabels[flows[form.flow].source] }}<span class="req" aria-hidden="true">*</span></label>
            <select id="int-source" name="source" class="select" required [(ngModel)]="form.sourceConnectionId" #sourceModel="ngModel"
                    [attr.aria-invalid]="sourceModel.invalid && (sourceModel.touched || ngForm.submitted)">
              <option value="" disabled>Elegí una conexión</option>
              @for (c of connectionsFor(flows[form.flow].source); track c.id) {
                <option [value]="c.id">{{ c.name }}</option>
              }
            </select>
            @if (sourceModel.invalid && (sourceModel.touched || ngForm.submitted)) {
              <span class="field-error" role="alert">Elegí la conexión de origen.</span>
            } @else if (!connectionsFor(flows[form.flow].source).length) {
              <span class="hint">No hay conexiones {{ connectorLabels[flows[form.flow].source] }} en este tenant.</span>
            }
          </div>
          <div class="field">
            <label for="int-target">Destino · {{ connectorLabels[flows[form.flow].target] }}<span class="req" aria-hidden="true">*</span></label>
            <select id="int-target" name="target" class="select" required [(ngModel)]="form.targetConnectionId" #targetModel="ngModel"
                    [attr.aria-invalid]="targetModel.invalid && (targetModel.touched || ngForm.submitted)">
              <option value="" disabled>Elegí una conexión</option>
              @for (c of connectionsFor(flows[form.flow].target); track c.id) {
                <option [value]="c.id">{{ c.name }}</option>
              }
            </select>
            @if (targetModel.invalid && (targetModel.touched || ngForm.submitted)) {
              <span class="field-error" role="alert">Elegí la conexión de destino.</span>
            } @else if (!connectionsFor(flows[form.flow].target).length) {
              <span class="hint">No hay conexiones {{ connectorLabels[flows[form.flow].target] }} en este tenant.</span>
            }
          </div>
          @if (form.flow === 'CATALOG') {
            <div class="field">
              <label for="pol-schedule">Sincronizar cada<span class="req" aria-hidden="true">*</span></label>
              <div class="input-affix">
                <input id="pol-schedule" name="pol-schedule" type="number" min="60" required class="input num"
                       [(ngModel)]="form.policy.scheduleEverySeconds" #scheduleModel="ngModel" aria-describedby="pol-schedule-hint"
                       [attr.aria-invalid]="scheduleModel.invalid && (scheduleModel.touched || ngForm.submitted)" />
                <span class="unit" aria-hidden="true">s</span>
              </div>
              @if (scheduleModel.invalid && (scheduleModel.touched || ngForm.submitted)) {
                <span class="field-error" role="alert">Mínimo 60 segundos.</span>
              }
              <span id="pol-schedule-hint" class="hint">Mínimo 60 s. Por defecto 300 s (5 min).</span>
            </div>
          }
        </app-form-section>

        <app-form-section heading="Política" description="Valores por defecto pensados para cada flujo. Cambialos solo si el destino lo necesita.">
          <details class="advanced" [open]="advancedOpen()" (toggle)="advancedOpen.set($any($event.target).open)">
            <summary>
              <app-icon name="chevron-right" />
              <span class="summary-text">
                <span class="choice-title">Avanzado</span>
                <span class="choice-note">{{ policySummary() }}</span>
              </span>
            </summary>
            <div class="advanced-body">
              @for (group of policyGroups; track group) {
                <div>
                  <h4 class="subhead">{{ group }}</h4>
                  <div class="policy-grid">
                    @for (field of fieldsOf(group); track field.key) {
                      <div class="field">
                        <label [for]="'pol-' + field.key">{{ field.label }}</label>
                        <div class="input-affix">
                          <input [id]="'pol-' + field.key" [name]="'pol-' + field.key" type="number" required class="input num"
                                 [min]="field.min" [max]="field.max ?? null" [attr.aria-describedby]="'pol-hint-' + field.key"
                                 [(ngModel)]="form.policy[field.key]" #policyModel="ngModel"
                                 [attr.aria-invalid]="policyModel.invalid && (policyModel.touched || ngForm.submitted)" />
                          @if (field.unit) {
                            <span class="unit" aria-hidden="true">{{ field.unit }}</span>
                          }
                        </div>
                        @if (policyModel.invalid && (policyModel.touched || ngForm.submitted)) {
                          <span class="field-error" role="alert">
                            {{ field.max ? 'Entre ' + field.min + ' y ' + field.max + '.' : 'Mínimo ' + field.min + '.' }}
                          </span>
                        }
                        <span class="hint" [id]="'pol-hint-' + field.key">
                          {{ field.hint }} Por defecto {{ defaults()[field.key] }}{{ field.unit ? ' ' + field.unit : '' }}.
                        </span>
                      </div>
                    }
                  </div>
                </div>
              }
              <button type="button" class="btn btn-sm restore" (click)="restoreDefaults()">
                <app-icon name="rotate-cw" />Restaurar valores por defecto
              </button>
            </div>
          </details>
        </app-form-section>
      </form>
      }
      <ng-container modal-footer>
        @if (error(); as message) {
          <p class="alert" role="alert">{{ message }}</p>
        }
        <button type="button" class="btn" (click)="closed.emit()">Cancelar</button>
        <button type="submit" form="integration-form" class="btn btn-primary" [disabled]="busy()" [attr.aria-busy]="busy()">
          {{ busy() ? 'Guardando…' : (editing() ? 'Guardar cambios' : 'Crear integración') }}
        </button>
      </ng-container>
    </app-modal>
  `,
  styles: `
    .policy-grid { display: grid; gap: var(--space-4); grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr)); }
    .restore { justify-self: start; }
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
  protected readonly policyGroups = POLICY_GROUPS;
  protected readonly connectorLabels = CONNECTOR_LABELS;
  protected readonly advancedOpen = signal(false);
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
      this.advancedOpen.set(false);
      if (target === 'new') {
        this.form = emptyDraft();
        this.tenantId.set(this.scope.tenantId() ?? '');
      } else {
        this.form = {
          flow: target.flow,
          code: target.code,
          sourceConnectionId: target.sourceConnectionId,
          targetConnectionId: target.targetConnectionId,
          policy: { ...defaultPolicy(target.flow), ...target.policy },
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
    const policy = { ...this.form.policy };
    if (policy.readTimeoutMs === defaultPolicy(this.form.flow).readTimeoutMs) {
      policy.readTimeoutMs = defaultPolicy(flow).readTimeoutMs;
    }
    this.form = { ...this.form, flow, policy, sourceConnectionId: '', targetConnectionId: '' };
  }

  protected defaults(): IntegrationPolicy {
    return defaultPolicy(this.form.flow);
  }

  protected policySummary(): string {
    const p = this.form.policy;
    const isDefault = POLICY_FIELDS.every((f) => p[f.key] === this.defaults()[f.key]);
    return `${p.ratePerSecond} op/s · ráfaga ${p.burst} · ${p.maxAttempts} intentos · timeout ${p.readTimeoutMs / 1000} s · ` +
      (isDefault ? 'por defecto' : 'personalizada');
  }

  protected fieldsOf(group: PolicyGroup) {
    return POLICY_FIELDS.filter((f) => f.group === group);
  }

  protected restoreDefaults(): void {
    this.form = { ...this.form, policy: { ...defaultPolicy(this.form.flow), scheduleEverySeconds: this.form.policy.scheduleEverySeconds } };
  }

  /** An invalid policy field hides inside "Avanzado": open it so the inline error is visible. */
  protected submit(valid: boolean | null): void {
    if (valid) {
      this.save();
      return;
    }
    if (POLICY_FIELDS.some((f) => !(Number(this.form.policy[f.key]) >= f.min && (!f.max || Number(this.form.policy[f.key]) <= f.max)))) {
      this.advancedOpen.set(true);
    }
  }

  private save(): void {
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
  return { flow: 'FISCAL', code: '', sourceConnectionId: '', targetConnectionId: '', policy: defaultPolicy('FISCAL') };
}
