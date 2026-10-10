import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

// Path data from Lucide (ISC license), the icon set the POS and SIFEN dashboards already use.
// Circles, rects and lines are written as paths so one <path> loop draws every icon.
const circle = (cx: number, cy: number, r: number) =>
  `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;
const rect = (x: number, y: number, w: number, h: number, r: number) =>
  `M${x + r} ${y}h${w - 2 * r}a${r} ${r} 0 0 1 ${r} ${r}v${h - 2 * r}a${r} ${r} 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a${r} ${r} 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a${r} ${r} 0 0 1 ${r} ${-r}z`;
const OCTAGON =
  'M2.586 16.726A2 2 0 0 1 2 15.312V8.688a2 2 0 0 1 .586-1.414l4.688-4.688A2 2 0 0 1 8.688 2h6.624a2 2 0 0 1 1.414.586l4.688 4.688A2 2 0 0 1 22 8.688v6.624a2 2 0 0 1-.586 1.414l-4.688 4.688a2 2 0 0 1-1.414.586H8.688a2 2 0 0 1-1.414-.586z';
const C10 = circle(12, 12, 10);

const ICONS = {
  'layout-grid': [rect(3, 3, 7, 7, 1), rect(14, 3, 7, 7, 1), rect(14, 14, 7, 7, 1), rect(3, 14, 7, 7, 1)],
  activity: [
    'M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2',
  ],
  workflow: [rect(3, 3, 8, 8, 2), 'M7 11v4a2 2 0 0 0 2 2h4', rect(13, 13, 8, 8, 2)],
  plug: ['M12 22v-5', 'M9 8V2', 'M15 8V2', 'M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z'],
  'key-round': [
    'M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z',
    'M16.5 7.5h.01',
  ],
  users: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M16 3.128a4 4 0 0 1 0 7.744', 'M22 21v-2a4 4 0 0 0-3-3.87', circle(9, 7, 4)],
  building: [
    'M10 12h4',
    'M10 8h4',
    'M14 21v-3a2 2 0 0 0-4 0v3',
    'M6 10H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-2',
    'M6 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16',
  ],
  'scroll-text': [
    'M15 12h-5',
    'M15 8h-5',
    'M19 17V5a2 2 0 0 0-2-2H4',
    'M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3',
  ],
  menu: ['M4 5h16', 'M4 12h16', 'M4 19h16'],
  x: ['M18 6 6 18', 'm6 6 12 12'],
  sun: [circle(12, 12, 4), 'M12 2v2', 'M12 20v2', 'm4.93 4.93 1.41 1.41', 'm17.66 17.66 1.41 1.41', 'M2 12h2', 'M20 12h2', 'm6.34 17.66-1.41 1.41', 'm19.07 4.93-1.41 1.41'],
  moon: ['M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401'],
  monitor: [rect(2, 3, 20, 14, 2), 'M8 21h8', 'M12 17v4'],
  'log-out': ['m16 17 5-5-5-5', 'M21 12H9', 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4'],
  'chevron-down': ['m6 9 6 6 6-6'],
  'chevron-right': ['m9 18 6-6-6-6'],
  'triangle-alert': ['m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3', 'M12 9v4', 'M12 17h.01'],
  'circle-check': [C10, 'm9 12 2 2 4-4'],
  'circle-x': [C10, 'm15 9-6 6', 'm9 9 6 6'],
  'circle-pause': [C10, 'M10 15V9', 'M14 15V9'],
  'circle-minus': [C10, 'M8 12h8'],
  'circle-play': [C10, 'M10 8.5v7l5.5-3.5z'],
  'circle-help': [C10, 'M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3', 'M12 17h.01'],
  info: [C10, 'M12 16v-4', 'M12 8h.01'],
  clock: ['M12 6v6l4 2', C10],
  'octagon-x': [OCTAGON, 'm15 9-6 6', 'm9 9 6 6'],
  'octagon-alert': [OCTAGON, 'M12 16h.01', 'M12 8v4'],
  ban: ['M4.929 4.929 19.07 19.071', C10],
  'arrow-down-to-line': ['M12 17V3', 'm6 11 6 6 6-6', 'M19 21H5'],
  heart: [
    'M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5',
  ],
  'heart-crack': [
    'M12.409 5.824c-.702.792-1.15 1.496-1.415 2.166l2.153 2.156a.5.5 0 0 1 0 .707l-2.293 2.293a.5.5 0 0 0 0 .707L12 15',
    'M13.508 20.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5a5.5 5.5 0 0 1 9.591-3.677.6.6 0 0 0 .818.001A5.5 5.5 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5z',
  ],
  loader: ['M21 12a9 9 0 1 1-6.219-8.56'],
  'rotate-cw': ['M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8', 'M21 3v5h-5'],
  'refresh-cw': ['M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8', 'M21 3v5h-5', 'M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16', 'M8 16H3v5'],
  inbox: ['M22 12h-6l-2 3h-4l-2-3H2', 'M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z'],
  'arrow-left': ['m12 19-7-7 7-7', 'M19 12H5'],
  plus: ['M5 12h14', 'M12 5v14'],
  receipt: ['M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z', 'M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8', 'M12 17.5v-11'],
  'shopping-bag': [
    'M16 10a4 4 0 0 1-8 0',
    'M3.103 6.034h17.794',
    'M3.4 5.467a2 2 0 0 0-.4 1.2V20a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6.667a2 2 0 0 0-.4-1.2l-2-2.667A2 2 0 0 0 17 2H7a2 2 0 0 0-1.6.8z',
  ],
  package: [
    'M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z',
    'M12 22V12',
    'M3.29 7 12 12l8.71-5',
    'm7.5 4.27 9 5.15',
  ],
  store: [
    'M15 21v-5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5',
    'M17.774 10.31a1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.451 0 1.12 1.12 0 0 0-1.548 0 2.5 2.5 0 0 1-3.452 0 1.12 1.12 0 0 0-1.549 0 2.5 2.5 0 0 1-3.77-3.248l2.889-4.184A2 2 0 0 1 7 2h10a2 2 0 0 1 1.653.873l2.895 4.192a2.5 2.5 0 0 1-3.774 3.244',
    'M4 10.95V19a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8.05',
  ],
  'file-text': [
    'M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z',
    'M14 2v5a1 1 0 0 0 1 1h5',
    'M10 9H8',
    'M16 13H8',
    'M16 17H8',
  ],
  gift: [rect(3, 8, 18, 4, 1), 'M12 8v13', 'M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7', 'M7.5 8a2.5 2.5 0 0 1 0-5A4.8 8 0 0 1 12 8a4.8 8 0 0 1 4.5-5 2.5 2.5 0 0 1 0 5'],
  list: ['M3 5h.01', 'M3 12h.01', 'M3 19h.01', 'M8 5h13', 'M8 12h13', 'M8 19h13'],
  'panel-left-close': [rect(3, 3, 18, 18, 2), 'M9 3v18', 'm16 15-3-3 3-3'],
  'panel-left-open': [rect(3, 3, 18, 18, 2), 'M9 3v18', 'm14 9 3 3-3 3'],
  search: ['m21 21-4.34-4.34', circle(11, 11, 8)],
  server: [rect(2, 2, 20, 8, 2), rect(2, 14, 20, 8, 2), 'M6 6h.01', 'M6 18h.01'],
  timer: ['M10 2h4', 'M12 14l3-3', circle(12, 14, 8)],
  pencil: [
    'M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z',
    'm15 5 4 4',
  ],
  eye: ['M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0', circle(12, 12, 3)],
  'eye-off': [
    'M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49',
    'M14.084 14.158a3 3 0 0 1-4.242-4.242',
    'M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143',
    'm2 2 20 20',
  ],
  'arrow-right-left': ['m16 3 4 4-4 4', 'M20 7H4', 'm8 21-4-4 4-4', 'M4 17h16'],
  network: [rect(16, 16, 6, 6, 1), rect(2, 16, 6, 6, 1), rect(9, 2, 6, 6, 1), 'M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3', 'M12 12V8'],
  'chart-pie': [
    'M21 12c.552 0 1.005-.449.95-.998a10 10 0 0 0-8.953-8.951c-.55-.055-.998.398-.998.95v8a1 1 0 0 0 1 1z',
    'M21.21 15.89A10 10 0 1 1 8 2.83',
  ],
  'ellipsis-vertical': [circle(12, 12, 1), circle(12, 5, 1), circle(12, 19, 1)],
  'zoom-in': [circle(11, 11, 8), 'm21 21-4.35-4.35', 'M11 8v6', 'M8 11h6'],
  'zoom-out': [circle(11, 11, 8), 'm21 21-4.35-4.35', 'M8 11h6'],
  maximize: ['M8 3H5a2 2 0 0 0-2 2v3', 'M21 8V5a2 2 0 0 0-2-2h-3', 'M3 16v3a2 2 0 0 0 2 2h3', 'M16 21h3a2 2 0 0 0 2-2v-3'],
  minus: ['M5 12h14'],
  play: ['M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z'],
  pause: [rect(14, 3, 5, 18, 1), rect(5, 3, 5, 18, 1)],
  history: ['M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8', 'M3 3v5h5', 'M12 7v5l4 2'],
  'arrow-right': ['M5 12h14', 'm12 5 7 7-7 7'],
  'chevron-up': ['m18 15-6-6-6 6'],
} satisfies Record<string, string[]>;

export type IconName = keyof typeof ICONS;

/**
 * Decorative stroke icon; the surrounding text or aria-label carries the meaning.
 * `spin` is only for the moment a request is in flight, never for a status.
 */
@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true', '[class.spin]': 'spin()' },
  template: `
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
         stroke-linejoin="round" focusable="false">
      @for (d of paths(); track $index) {
        <path [attr.d]="d" />
      }
    </svg>
  `,
  styles: `
    :host { display: inline-flex; flex: none; width: var(--icon-size, 1rem); height: var(--icon-size, 1rem); }
    svg { width: 100%; height: 100%; }
    :host(.spin) svg { animation: icon-spin 0.8s linear infinite; }
    @media (prefers-reduced-motion: reduce) { :host(.spin) svg { animation: none; } }
    @keyframes icon-spin { to { transform: rotate(360deg); } }
  `,
})
export class Icon {
  readonly name = input.required<IconName>();
  readonly spin = input(false);
  protected readonly paths = computed(() => ICONS[this.name()]);
}
