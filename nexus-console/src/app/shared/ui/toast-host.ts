import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Notifier } from '../../core/notifier.service';

@Component({
  selector: 'app-toast-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toasts" aria-live="polite">
      @for (toast of notifier.toasts(); track toast.id) {
        <div class="toast" [class.toast-error]="toast.kind === 'error'" [attr.role]="toast.kind === 'error' ? 'alert' : 'status'">
          <span>{{ toast.message }}</span>
          <button type="button" class="btn btn-ghost btn-sm" (click)="notifier.dismiss(toast.id)" aria-label="Cerrar aviso">✕</button>
        </div>
      }
    </div>
  `,
  styles: `
    .toasts {
      position: fixed;
      inset: auto var(--space-4) var(--space-4) auto;
      z-index: 10;
      display: grid;
      gap: var(--space-2);
      width: min(26rem, calc(100vw - 2 * var(--space-4)));
    }
    .toast {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      padding: var(--space-2) var(--space-2) var(--space-2) var(--space-4);
      border-radius: var(--radius-md);
      border-left: 4px solid var(--color-ok);
      background: var(--color-surface);
      box-shadow: var(--shadow-lg);
      font-size: var(--text-sm);
    }
    .toast-error { border-left-color: var(--color-danger); }
  `,
})
export class ToastHost {
  protected readonly notifier = inject(Notifier);
}
