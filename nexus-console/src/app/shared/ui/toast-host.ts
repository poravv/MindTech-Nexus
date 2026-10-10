import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Notifier } from '../../core/notifier.service';
import { Icon } from './icon';

@Component({
  selector: 'app-toast-host',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toasts" aria-live="polite">
      @for (toast of notifier.toasts(); track toast.id) {
        <div class="toast" [class.toast-error]="toast.kind === 'error'" [attr.role]="toast.kind === 'error' ? 'alert' : 'status'">
          <app-icon class="toast-icon" [name]="toast.kind === 'error' ? 'circle-x' : 'circle-check'" />
          <span class="toast-text">{{ toast.message }}</span>
          <button type="button" class="btn btn-ghost btn-sm btn-icon" (click)="notifier.dismiss(toast.id)" aria-label="Cerrar aviso">
            <app-icon name="x" />
          </button>
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
      gap: var(--space-3);
      padding: var(--space-2) var(--space-2) var(--space-2) var(--space-3);
      border-radius: var(--radius-lg);
      border: var(--border-width) solid var(--color-border);
      background: var(--color-surface);
      box-shadow: var(--shadow-lg);
      font-size: var(--text-sm);
      transition: opacity var(--duration) ease, transform var(--duration) var(--ease-out);
      @starting-style { opacity: 0; transform: translateY(8px); }
    }
    .toast-icon { --icon-size: 1.125rem; color: var(--color-ok); }
    .toast-error .toast-icon { color: var(--color-danger); }
    .toast-text { flex: 1; }
  `,
})
export class ToastHost {
  protected readonly notifier = inject(Notifier);
}
