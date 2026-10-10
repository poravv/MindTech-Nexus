import { ChangeDetectionStrategy, Component, effect, ElementRef, input, output, viewChild } from '@angular/core';

let nextId = 0;

/**
 * Native modal <dialog>: the browser traps focus, closes on Escape and returns focus to the opener.
 * The parent owns `open`; the dialog reports every way of closing through `closed`.
 */
@Component({
  selector: 'app-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog #dialog class="modal" [attr.aria-labelledby]="headingId" (close)="closed.emit()">
      <header class="modal-head">
        <h2 [id]="headingId">{{ heading() }}</h2>
        <button type="button" class="btn btn-ghost btn-sm" (click)="dialog.close()" aria-label="Cerrar">✕</button>
      </header>
      <div class="modal-body"><ng-content /></div>
    </dialog>
  `,
  styles: `
    .modal {
      width: min(var(--dialog-width), calc(100vw - 2 * var(--space-4)));
      max-height: calc(100dvh - 2 * var(--space-4));
      padding: 0;
      border: var(--border-width) solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-surface);
      color: var(--color-text);
      box-shadow: var(--shadow-lg);
    }
    .modal::backdrop { background: var(--backdrop); }
    .modal-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      padding: var(--space-4) var(--space-4) 0;
    }
    .modal-body { padding: var(--space-4); }
  `,
})
export class Modal {
  readonly heading = input.required<string>();
  readonly open = input(false);
  readonly closed = output<void>();

  protected readonly headingId = `modal-heading-${++nextId}`;
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    effect(() => {
      const dialog = this.dialog().nativeElement;
      if (this.open() && !dialog.open) {
        dialog.showModal();
      } else if (!this.open() && dialog.open) {
        dialog.close();
      }
    });
  }
}
