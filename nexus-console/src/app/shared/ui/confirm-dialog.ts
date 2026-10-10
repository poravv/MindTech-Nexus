import { ChangeDetectionStrategy, Component, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Modal } from './modal';

let nextId = 0;

/**
 * Asks before a sensitive action. With `requireReason` the reason is mandatory and goes to the audit trail;
 * with `confirmText` (destructive actions) the user must type that exact text, usually the target's name.
 */
@Component({
  selector: 'app-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Modal, FormsModule],
  template: `
    <app-modal [heading]="heading()" [open]="open()" (closed)="cancelled.emit()">
      @if (open()) {
        <form [id]="formId" class="form" (ngSubmit)="canConfirm() && confirm()">
          <p>{{ message() }}</p>
          @if (requireReason()) {
            <div class="field">
              <label [for]="formId + '-reason'">Motivo<span class="req" aria-hidden="true">*</span></label>
              <textarea [id]="formId + '-reason'" name="reason" class="textarea" required maxlength="500"
                        [(ngModel)]="reason" [attr.aria-describedby]="formId + '-reason-hint'"></textarea>
              <span [id]="formId + '-reason-hint'" class="hint">Queda registrado en la auditoría.</span>
            </div>
          }
          @if (confirmText()) {
            <div class="field">
              <label [for]="formId + '-typed'">Escribí <span class="mono">{{ confirmText() }}</span> para confirmar</label>
              <input [id]="formId + '-typed'" name="typed" class="input mono" required autocomplete="off" spellcheck="false"
                     [(ngModel)]="typed" />
            </div>
          }
        </form>
      }
      <ng-container modal-footer>
        <button type="button" class="btn" (click)="cancelled.emit()">Cancelar</button>
        <button type="submit" [attr.form]="formId" class="btn" [class.btn-danger]="danger()" [class.btn-primary]="!danger()"
                [disabled]="busy() || !canConfirm()" [attr.aria-busy]="busy()">
          {{ busy() ? 'Procesando…' : confirmLabel() }}
        </button>
      </ng-container>
    </app-modal>
  `,
})
export class ConfirmDialog {
  readonly heading = input.required<string>();
  readonly message = input.required<string>();
  readonly confirmLabel = input('Confirmar');
  readonly danger = input(false);
  readonly requireReason = input(false);
  readonly confirmText = input('');
  readonly open = input(false);
  readonly busy = input(false);
  readonly confirmed = output<string>();
  readonly cancelled = output<void>();

  protected readonly formId = `confirm-${++nextId}`;
  protected readonly reason = signal('');
  protected readonly typed = signal('');

  constructor() {
    effect(() => {
      if (this.open()) {
        this.reason.set('');
        this.typed.set('');
      }
    });
  }

  protected canConfirm(): boolean {
    return (!this.requireReason() || this.reason().trim() !== '') && (!this.confirmText() || this.typed().trim() === this.confirmText());
  }

  protected confirm(): void {
    this.confirmed.emit(this.reason().trim());
  }
}
