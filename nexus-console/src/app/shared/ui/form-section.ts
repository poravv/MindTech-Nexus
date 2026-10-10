import { ChangeDetectionStrategy, Component, input } from '@angular/core';

let nextId = 0;

/** A titled group of form fields laid out in one or two columns (one on phones). */
@Component({
  selector: 'app-form-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="section" [attr.aria-labelledby]="headingId">
      <div class="section-head">
        <h3 [id]="headingId">{{ heading() }}</h3>
        @if (description()) {
          <p>{{ description() }}</p>
        }
      </div>
      <div class="section-fields" [class.two]="columns() === 2"><ng-content /></div>
    </section>
  `,
  styles: `
    :host { display: block; }
    :host + :host { margin-top: var(--space-5); padding-top: var(--space-5); border-top: var(--border-width) solid var(--color-border); }
    .section { display: grid; gap: var(--space-4); }
    .section-head { display: grid; gap: var(--space-1); }
    .section-head h3 { font-size: var(--text-lg); font-weight: var(--weight-semibold); }
    .section-head p { color: var(--color-text-muted); font-size: var(--text-xs); }
    .section-fields { display: grid; gap: var(--space-4); grid-template-columns: minmax(0, 1fr); }
    @media (min-width: 40rem) {
      .section-fields.two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }
  `,
})
export class FormSection {
  readonly heading = input.required<string>();
  readonly description = input('');
  readonly columns = input<1 | 2>(1);

  protected readonly headingId = `form-section-${++nextId}`;
}
