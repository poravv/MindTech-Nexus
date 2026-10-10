import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon, IconName } from './icon';

export interface BarItem {
  key: string;
  label: string;
  value: number;
  icon?: IconName;
  link?: string;
  queryParams?: Record<string, string>;
}

/** Single-series horizontal bars (magnitude per category), labelled on the left, value on the right. */
@Component({
  selector: 'app-bar-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon],
  template: `
    <ul class="bars">
      @for (bar of bars(); track bar.key) {
        <li>
          @if (bar.link) {
            <a class="label" [routerLink]="bar.link" [queryParams]="bar.queryParams">
              @if (bar.icon) { <app-icon [name]="bar.icon" /> }{{ bar.label }}
            </a>
          } @else {
            <span class="label">@if (bar.icon) { <app-icon [name]="bar.icon" /> }{{ bar.label }}</span>
          }
          <span class="track" aria-hidden="true"><span class="bar" [style.width.%]="bar.width" [attr.title]="bar.label + ': ' + bar.value"></span></span>
          <span class="value num">{{ bar.value }}</span>
        </li>
      }
    </ul>
  `,
  styles: `
    .bars { display: grid; gap: var(--space-4); margin: 0; padding: 0; list-style: none; }
    li { display: grid; grid-template-columns: minmax(7rem, 13rem) minmax(0, 1fr) 3.5rem; align-items: center; gap: var(--space-3); }
    .label { --icon-size: 1.25rem; display: inline-flex; align-items: center; gap: var(--space-2); min-width: 0; color: var(--color-text);
      font-size: var(--text-md); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .label app-icon { color: var(--color-text-muted); }
    .track { height: 0.875rem; border-radius: 4px; background: var(--chart-track); overflow: hidden; }
    .bar { display: block; height: 100%; border-radius: 4px; background: var(--chart-bar); transition: width var(--duration) var(--ease-out); }
    .value { text-align: right; font-size: var(--text-lg); }
  `,
})
export class BarList {
  readonly items = input.required<BarItem[]>();
  protected readonly bars = computed(() => {
    const max = Math.max(1, ...this.items().map((item) => item.value));
    return this.items().map((item) => ({ ...item, width: (item.value / max) * 100 }));
  });
}
