import { ChangeDetectionStrategy, Component, input } from '@angular/core';

let nextId = 0;

/**
 * MindTech Nexus logo: the "N" drawn as a link between two nodes, on the MindTech violet → Nexus blue tile.
 * Same drawing as src/assets/brand/*.svg; the wordmark is HTML so it follows the theme's text color.
 */
@Component({
  selector: 'app-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg class="mark" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient [attr.id]="gradientId" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop stop-color="#8b5cf6" />
          <stop offset="1" stop-color="#3b6cf6" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="8" [attr.fill]="'url(#' + gradientId + ')'" />
      <path d="M10 22.5V9.5l12 13V9.5" fill="none" stroke="#fff" stroke-width="2.8" stroke-linecap="round"
            stroke-linejoin="round" />
      <circle cx="10" cy="9.5" r="2.7" fill="#fff" />
      <circle cx="22" cy="22.5" r="2.7" fill="#fff" />
    </svg>
    @if (withText()) {
      <span class="text">
        <span class="name">Nexus</span>
        <span class="sub">by MindTechPy</span>
      </span>
    }
  `,
  styles: `
    :host { display: inline-flex; align-items: center; gap: 0.625em; font-size: var(--logo-size, 1rem); line-height: 1; }
    .mark { flex: none; width: 2em; height: 2em; }
    .text { display: grid; gap: 0.2em; }
    .name { font-size: 1.15em; font-weight: var(--weight-bold); letter-spacing: var(--tracking-tight); }
    .sub { font-size: max(0.62em, var(--text-2xs)); font-weight: var(--weight-medium); letter-spacing: 0.02em; opacity: 0.72; }
  `,
})
export class Logo {
  readonly withText = input(true);
  protected readonly gradientId = `nexus-logo-${++nextId}`;
}
