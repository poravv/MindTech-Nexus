import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Integration, IntegrationsApi } from '../../api/integrations.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { allowedIntegrationStates, IntegrationState } from '../../core/roles';
import { Icon } from '../../shared/ui/icon';
import { Modal } from '../../shared/ui/modal';
import { StatusBadge } from '../../shared/ui/status-badge';

const STATE_HELP: Record<IntegrationState, string> = {
  ENABLED: 'Procesa operaciones normalmente.',
  PAUSED: 'Retiene las operaciones sin consumir intentos; se reanudan al habilitar.',
  DRAINING: 'Termina las operaciones en curso y rechaza nuevas.',
  DISABLED: 'No despacha ni acepta operaciones nuevas.',
  EMERGENCY_STOP: 'Corta todo de inmediato. Solo administrador de plataforma.',
};

const STATE_LABELS: Record<IntegrationState, string> = {
  ENABLED: 'Habilitada',
  PAUSED: 'Pausada',
  DRAINING: 'Drenando',
  DISABLED: 'Deshabilitada',
  EMERGENCY_STOP: 'Parada de emergencia',
};

/**
 * Changes an integration's state with a mandatory reason (it goes to the audit trail).
 * Open it by setting `integration`; it emits `closed` on cancel and `changed` after a successful change.
 */
@Component({
  selector: 'app-integration-state-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Icon, Modal, StatusBadge],
  template: `
    <app-modal [heading]="'Cambiar estado'" [description]="integration() ? integration()!.code : ''"
               [open]="integration() !== null" (closed)="closed.emit()">
      @if (integration(); as current) {
        <form id="state-form" class="form" (ngSubmit)="canSubmit(current) && submit(current)" #stateForm="ngForm">
          <p class="current">Estado actual <app-status-badge [status]="current.state" /></p>
          <fieldset>
            <legend>Nuevo estado</legend>
            <div class="choice-grid stacked">
              @for (state of targetsFrom(current.state); track state) {
                <label class="choice compact">
                  <input type="radio" name="state" [value]="state" [(ngModel)]="next" />
                  <span class="choice-text">
                    <app-status-badge [status]="state" />
                    <span class="choice-note">{{ help[state] }}</span>
                  </span>
                  <app-icon class="choice-check" name="circle-check" />
                </label>
              }
            </div>
          </fieldset>
          <div class="field">
            <label for="state-reason">Motivo<span class="req" aria-hidden="true">*</span></label>
            <textarea id="state-reason" name="reason" class="textarea" required maxlength="500" [(ngModel)]="reason"
                      #reasonModel="ngModel" aria-describedby="state-reason-hint" placeholder="Ej.: mantenimiento del POS hasta las 18:00"
                      [attr.aria-invalid]="reasonModel.invalid && (reasonModel.touched || stateForm.submitted)"></textarea>
            @if (reasonModel.invalid && (reasonModel.touched || stateForm.submitted)) {
              <span class="field-error" role="alert">Contá por qué cambia el estado.</span>
            }
            <span id="state-reason-hint" class="hint">Queda registrado en la auditoría.</span>
          </div>
          @if (next === 'EMERGENCY_STOP') {
            <div class="field">
              <label for="state-confirm">Escribí <span class="mono">{{ current.code }}</span> para confirmar<span class="req" aria-hidden="true">*</span></label>
              <input id="state-confirm" name="confirmText" class="input mono" required autocomplete="off" spellcheck="false"
                     [(ngModel)]="confirmText" aria-describedby="state-confirm-hint" />
              <span id="state-confirm-hint" class="hint">La parada de emergencia corta todo de inmediato.</span>
            </div>
          }
        </form>
      }
      <ng-container modal-footer>
        @if (error(); as message) {
          <p class="alert" role="alert">{{ message }}</p>
        }
        <button type="button" class="btn" (click)="closed.emit()">Cancelar</button>
        @if (integration(); as current) {
          <button type="submit" form="state-form" class="btn" [class.btn-danger]="next === 'EMERGENCY_STOP'"
                  [class.btn-primary]="next !== 'EMERGENCY_STOP'" [disabled]="busy() || !canConfirm(current)" [attr.aria-busy]="busy()">
            {{ busy() ? 'Cambiando…' : 'Cambiar a ' + label(next) }}
          </button>
        }
      </ng-container>
    </app-modal>
  `,
  styles: `
    .current { display: flex; align-items: center; gap: var(--space-2); color: var(--color-text-muted); }
    .choice.compact { padding: var(--space-2) var(--space-3); }
    .choice.compact .choice-text { justify-items: start; }
  `,
})
export class IntegrationStateDialog {
  private readonly api = inject(IntegrationsApi);
  private readonly auth = inject(AuthService);
  private readonly notifier = inject(Notifier);

  readonly integration = input<Integration | null>(null);
  /** State preselected on open (e.g. PAUSED from a "Pausar" button); otherwise the first one that changes something. */
  readonly suggested = input<IntegrationState | null>(null);
  readonly closed = output<void>();
  readonly changed = output<void>();

  protected readonly help = STATE_HELP;
  protected readonly states = computed(() => {
    const role = this.auth.role();
    return role ? allowedIntegrationStates(role) : [];
  });
  protected next: IntegrationState = 'PAUSED';
  protected reason = '';
  protected confirmText = '';
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const current = this.integration();
      if (current) {
        const suggested = this.suggested();
        this.next =
          suggested && suggested !== current.state && this.states().includes(suggested)
            ? suggested
            : (this.states().find((state) => state !== current.state) ?? 'PAUSED');
        this.reason = '';
        this.confirmText = '';
        this.error.set(null);
      }
    });
  }

  /** Everything but the reason, which shows its own inline error on submit. */
  protected canConfirm(current: Integration): boolean {
    return this.next !== current.state && (this.next !== 'EMERGENCY_STOP' || this.confirmText.trim() === current.code);
  }

  protected canSubmit(current: Integration): boolean {
    return this.canConfirm(current) && this.reason.trim() !== '';
  }

  protected targetsFrom(current: IntegrationState): IntegrationState[] {
    return this.states().filter((state) => state !== current);
  }

  protected label(state: IntegrationState): string {
    return STATE_LABELS[state];
  }

  protected submit(current: Integration): void {
    this.busy.set(true);
    this.api.changeState(current, this.next, this.reason.trim()).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifier.success(`${current.code}: estado cambiado.`);
        this.changed.emit();
      },
      error: (error) => {
        this.busy.set(false);
        this.error.set(problemMessage(error));
      },
    });
  }
}
