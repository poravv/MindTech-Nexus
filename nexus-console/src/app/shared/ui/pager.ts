import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/** Previous/next paging for lists that return one page at a time without a total. */
@Component({
  selector: 'app-pager',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="pager" aria-label="Paginación">
      <button type="button" class="btn btn-sm" [disabled]="page() === 0" (click)="pageChange.emit(page() - 1)">Anterior</button>
      <span class="muted" aria-live="polite">Página {{ page() + 1 }}</span>
      <button type="button" class="btn btn-sm" [disabled]="!hasNext()" (click)="pageChange.emit(page() + 1)">Siguiente</button>
    </nav>
  `,
  styles: `
    .pager {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: var(--space-3);
      padding: var(--space-2) var(--space-4);
      font-size: var(--text-sm);
      border-top: var(--border-width) solid var(--color-border);
    }
  `,
})
export class Pager {
  readonly page = input.required<number>();
  readonly hasNext = input.required<boolean>();
  readonly pageChange = output<number>();
}
