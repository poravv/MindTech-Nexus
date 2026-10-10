import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Integration, IntegrationsApi } from '../../api/integrations.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { allowedIntegrationStates, IntegrationState } from '../../core/roles';
import { Modal } from '../../shared/ui/modal';
import { StatusBadge } from '../../shared/ui/status-badge';

const STATE_HELP: Record<IntegrationState, string> = {
  ENABLED: 'Procesa operaciones normalmente.',
  PAUSED: 'Retiene las operaciones sin consumir intentos; se reanudan al habilitar.',
  DRAINING: 'Termina las operaciones en curso y rechaza nuevas.',
  DISABLED: 'No despacha ni acepta operaciones nuevas.',
  EMERGENCY_STOP: 'Corta todo de inmediato. Solo administrador de plataforma.',
};

/**
 * Changes an integration's state with a mandatory reason (it goes to the audit trail).
 * Open it by setting `integration`; it emits `closed` on cancel and `changed` after a successful change.
 */
@Component({
  selector: 'app-integration-state-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Modal, StatusBadge],
  template: `
    <app-modal heading="Cambiar estado" [open]="integration() !== null" (closed)="closed.emit()">
      @if (integration(); as current) {
        <form class="form" (ngSubmit)="submit(current)" #stateForm="ngForm">
          @if (error(); as message) {
            <p class="alert" role="alert">{{ message }}</p>
          }
          <p>
            <span class="mono">{{ current.code }}</span> está en <app-status-badge [status]="current.state" />.
          </p>
          <fieldset>
            <legend>Nuevo estado</legend>
            <div class="options">
              @for (state of states(); track state) {
                <label class="option" [class.disabled]="state === current.state">
                  <input type="radio" name="state" [value]="state" [(ngModel)]="next" [disabled]="state === current.state" />
                  <span><app-status-badge [status]="state" /><span class="hint">{{ help[state] }}</span></span>
                </label>
              }
            </div>
          </fieldset>
          <div class="field">
            <label for="state-reason">Motivo</label>
            <textarea id="state-reason" name="reason" class="textarea" required maxlength="500" [(ngModel)]="reason"
                      aria-describedby="state-reason-hint"></textarea>
            <span id="state-reason-hint" class="hint">Obligatorio. Queda registrado en la auditoría.</span>
          </div>
          <div class="form-actions">
            <button type="button" class="btn" (click)="closed.emit()">Cancelar</button>
            <button type="submit" class="btn" [class.btn-danger]="next === 'EMERGENCY_STOP'"
                    [class.btn-primary]="next !== 'EMERGENCY_STOP'"
                    [disabled]="busy() || stateForm.invalid || !reason.trim() || next === current.state">
              Confirmar cambio
            </button>
          </div>
        </form>
      }
    </app-modal>
  `,
  styles: `
    .options { display: grid; gap: var(--space-2); }
    .option {
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
      padding: var(--space-2) var(--space-3);
      border: var(--border-width) solid var(--color-border);
      border-radius: var(--radius-md);
      cursor: pointer;
    }
    .option:has(input:checked) { border-color: var(--color-accent); background: var(--color-accent-soft); }
    .option.disabled { opacity: 0.5; cursor: not-allowed; }
    .option input { margin-top: 0.2rem; accent-color: var(--color-accent); }
    .option > span { display: grid; gap: var(--space-1); justify-items: start; }
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
        this.error.set(null);
      }
    });
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
