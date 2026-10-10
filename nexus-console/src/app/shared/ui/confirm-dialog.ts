import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Modal } from './modal';

/** Asks before a sensitive action; with `requireReason` the reason is mandatory and goes to the audit trail. */
@Component({
  selector: 'app-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Modal, FormsModule],
  template: `
    <app-modal [heading]="heading()" [open]="open()" (closed)="cancelled.emit()">
      <form class="form" (ngSubmit)="confirm()">
        <p>{{ message() }}</p>
        @if (requireReason()) {
          <div class="field">
            <label for="confirm-reason">Motivo</label>
            <textarea id="confirm-reason" name="reason" class="textarea" required maxlength="500"
                      [(ngModel)]="reason" aria-describedby="confirm-reason-hint"></textarea>
            <span id="confirm-reason-hint" class="hint">Queda registrado en la auditoría.</span>
          </div>
        }
        <div class="form-actions">
          <button type="button" class="btn" (click)="cancelled.emit()">Cancelar</button>
          <button type="submit" class="btn" [class.btn-danger]="danger()" [class.btn-primary]="!danger()"
                  [disabled]="busy() || (requireReason() && !reason().trim())">
            {{ confirmLabel() }}
          </button>
        </div>
      </form>
    </app-modal>
  `,
})
export class ConfirmDialog {
  readonly heading = input.required<string>();
  readonly message = input.required<string>();
  readonly confirmLabel = input('Confirmar');
  readonly danger = input(false);
  readonly requireReason = input(false);
  readonly open = input(false);
  readonly busy = input(false);
  readonly confirmed = output<string>();
  readonly cancelled = output<void>();

  protected readonly reason = signal('');

  protected confirm(): void {
    this.confirmed.emit(this.reason().trim());
    this.reason.set('');
  }
}
