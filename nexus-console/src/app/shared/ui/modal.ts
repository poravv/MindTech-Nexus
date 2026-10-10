import { ChangeDetectionStrategy, Component, effect, ElementRef, input, output, viewChild } from '@angular/core';
import { Icon } from './icon';

let nextId = 0;

/**
 * Native modal <dialog>: the browser traps focus, closes on Escape and returns focus to the opener.
 * `sheet` slides in from the right for create/edit forms (full screen on phones); `dialog` is a compact centered
 * box for confirmations. The body scrolls; content marked `modal-footer` stays pinned at the bottom.
 * The parent owns `open`; the dialog reports every way of closing through `closed`.
 */
@Component({
  selector: 'app-modal',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog #dialog class="modal" [class.sheet]="variant() === 'sheet'" [attr.aria-labelledby]="headingId"
            [attr.aria-describedby]="description() ? descriptionId : null" (close)="closed.emit()">
      <header class="modal-head">
        <div class="modal-title">
          <h2 [id]="headingId">{{ heading() }}</h2>
          @if (description()) {
            <p [id]="descriptionId">{{ description() }}</p>
          }
        </div>
        <button type="button" class="btn btn-ghost btn-icon" (click)="dialog.close()" aria-label="Cerrar">
          <app-icon name="x" />
        </button>
      </header>
      <div class="modal-body"><ng-content /></div>
      <footer class="modal-foot"><ng-content select="[modal-footer]" /></footer>
    </dialog>
  `,
  styles: `
    .modal {
      display: none;
      flex-direction: column;
      width: min(var(--dialog-width), calc(100vw - 2 * var(--space-4)));
      max-height: calc(100dvh - 2 * var(--space-4));
      padding: 0;
      border: var(--border-width) solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-surface);
      color: var(--color-text);
      box-shadow: var(--shadow-lg);
      overflow: hidden;
    }
    .modal[open] {
      display: flex;
      transition: opacity var(--duration) ease, transform var(--duration) var(--ease-out);
      @starting-style { opacity: 0; transform: scale(0.97); }
    }
    .modal::backdrop { background: var(--backdrop); }

    .sheet {
      inset: 0 0 0 auto;
      width: min(var(--sheet-width), 100vw);
      max-width: none;
      height: 100dvh;
      max-height: none;
      margin: 0;
      border-width: 0 0 0 var(--border-width);
      border-radius: 0;
    }
    .sheet[open] {
      @starting-style { opacity: 1; transform: translateX(var(--space-6)); }
    }

    .modal-head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--space-3);
      padding: var(--space-5) var(--space-4) var(--space-4) var(--space-5);
      border-bottom: var(--border-width) solid var(--color-border);
    }
    .modal-title { display: grid; gap: var(--space-1); min-width: 0; }
    .modal-title h2 { font-size: var(--text-xl); font-weight: var(--weight-semibold); }
    .sheet .modal-title h2 { font-size: var(--text-2xl); font-weight: var(--weight-medium); }
    .modal-title p { color: var(--color-text-muted); font-size: var(--text-md); }

    .modal-body {
      flex: 1 1 auto;
      min-height: 0;
      overflow-y: auto;
      overscroll-behavior: contain;
      padding: var(--space-5);
    }

    .modal-foot {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: flex-end;
      gap: var(--space-2);
      padding: var(--space-3) var(--space-5);
      border-top: var(--border-width) solid var(--color-border);
      background: var(--color-surface-sunken);
    }
    .modal-foot:empty { display: none; }
  `,
})
export class Modal {
  readonly heading = input.required<string>();
  readonly description = input<string>('');
  readonly variant = input<'dialog' | 'sheet'>('dialog');
  readonly open = input(false);
  readonly closed = output<void>();

  protected readonly headingId = `modal-heading-${++nextId}`;
  protected readonly descriptionId = `modal-description-${nextId}`;
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
