import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon, IconName } from './icon';
import { Tone } from './status-badge';

export interface DonutSegment {
  key: string;
  label: string;
  value: number;
  /** Chart fill token, e.g. var(--chart-ok). */
  fill: string;
  icon: IconName;
  tone: Tone;
  link?: string;
  queryParams?: Record<string, string>;
}

const RADIUS = 42;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
/** Surface gap between segments, in circumference units. */
const GAP = 1.6;

/** Share-of-total ring with the total in the middle and a legend table (icon + label + count + share). */
@Component({
  selector: 'app-donut-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon],
  template: `
    <div class="donut-wrap">
      <svg class="donut" viewBox="0 0 100 100" role="img" [attr.aria-label]="total() + ' ' + caption()">
        <circle class="track" cx="50" cy="50" [attr.r]="radius" />
        @for (arc of arcs(); track arc.key) {
          @if (arc.value) {
            <circle class="segment" cx="50" cy="50" [attr.r]="radius" [style.stroke]="arc.fill"
                    [attr.stroke-dasharray]="arc.dash" [attr.stroke-dashoffset]="arc.offset">
              <title>{{ arc.label }}: {{ arc.value }} ({{ arc.share }} %)</title>
            </circle>
          }
        }
        <text x="50" y="50" class="total">{{ total() }}</text>
        <text x="50" y="63" class="caption">{{ caption() }}</text>
      </svg>
      <table class="legend">
        <caption class="visually-hidden">{{ caption() }}</caption>
        <tbody>
          @for (arc of arcs(); track arc.key) {
            <tr [class.zero]="!arc.value">
              <th scope="row">
                <span class="swatch" [style.background]="arc.fill"></span>
                @if (arc.link) {
                  <a [routerLink]="arc.link" [queryParams]="arc.queryParams" [class]="'name tone-' + arc.tone"><app-icon [name]="arc.icon" />{{ arc.label }}</a>
                } @else {
                  <span [class]="'name tone-' + arc.tone"><app-icon [name]="arc.icon" />{{ arc.label }}</span>
                }
              </th>
              <td class="num">{{ arc.value }}</td>
              <td class="num share">{{ arc.share }} %</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .donut-wrap { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-5) var(--space-6); }
    .donut { flex: none; width: 13rem; height: 13rem; transform: rotate(-90deg); }
    .donut circle { fill: none; stroke-width: 12; }
    .track { stroke: var(--chart-track); }
    .segment { transition: stroke-width var(--duration-fast) ease, opacity var(--duration-fast) ease; }
    .segment:hover { stroke-width: 15; }
    .donut:hover .segment:not(:hover) { opacity: 0.55; }
    .donut text { transform: rotate(90deg); transform-origin: 50px 50px; text-anchor: middle; fill: var(--color-text); }
    .total { font-size: 22px; font-weight: var(--weight-medium); font-variant-numeric: tabular-nums; }
    .caption { font-size: 7.5px; fill: var(--color-text-muted) !important; }
    .legend { flex: 1 1 15rem; border-collapse: collapse; font-size: var(--text-md); }
    .legend th, .legend td { padding: var(--space-2) var(--space-2); text-align: left; font-weight: var(--weight-regular); }
    .legend tr + tr th, .legend tr + tr td { border-top: var(--border-width) solid var(--color-border); }
    .legend td { text-align: right; width: 1%; white-space: nowrap; }
    .share { color: var(--color-text-muted); }
    .zero { color: var(--color-text-faint); }
    .swatch { display: inline-block; width: 0.75rem; height: 0.75rem; margin-right: var(--space-2); border-radius: 3px; vertical-align: -1px; }
    .name { --icon-size: 1.125rem; display: inline-flex; align-items: center; gap: var(--space-2); color: var(--color-text); }
    .name app-icon { color: var(--tone); }
    .tone-ok { --tone: var(--color-ok); } .tone-info { --tone: var(--color-info); } .tone-warn { --tone: var(--color-warn); }
    .tone-serious { --tone: var(--color-serious); } .tone-danger { --tone: var(--color-danger); } .tone-neutral { --tone: var(--color-neutral); }
  `,
})
export class DonutChart {
  readonly segments = input.required<DonutSegment[]>();
  readonly caption = input('');

  protected readonly radius = RADIUS;
  protected readonly total = computed(() => this.segments().reduce((sum, segment) => sum + segment.value, 0));
  protected readonly arcs = computed(() => {
    const total = this.total() || 1;
    let offset = 0;
    return this.segments().map((segment) => {
      const length = (segment.value / total) * CIRCUMFERENCE;
      const arc = {
        ...segment,
        share: Math.round((segment.value / total) * 1000) / 10,
        dash: `${Math.max(length - GAP, 0.5)} ${CIRCUMFERENCE}`,
        offset: -offset,
      };
      offset += length;
      return arc;
    });
  });
}
