import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Icon, IconName } from './icon';

export type Tone = 'ok' | 'warn' | 'serious' | 'danger' | 'info' | 'neutral';

interface StatusMeta {
  label: string;
  tone: Tone;
  icon: IconName;
  spin?: boolean;
}

/** Label, tone and icon of every status the console shows (tenants, users, clients, integrations, operations, health). */
export const STATUS_META: Record<string, StatusMeta> = {
  ACTIVE: { label: 'Activo', tone: 'ok', icon: 'circle-check' },
  ENABLED: { label: 'Habilitada', tone: 'ok', icon: 'circle-check' },
  SUCCEEDED: { label: 'Exitosa', tone: 'ok', icon: 'circle-check' },
  PENDING: { label: 'Pendiente', tone: 'neutral', icon: 'clock' },
  RUNNING: { label: 'En curso', tone: 'info', icon: 'loader', spin: true },
  RETRY: { label: 'Reintento', tone: 'warn', icon: 'rotate-cw' },
  PAUSED: { label: 'Pausada', tone: 'warn', icon: 'circle-pause' },
  DRAINING: { label: 'Drenando', tone: 'warn', icon: 'arrow-down-to-line' },
  FAILED: { label: 'Fallida', tone: 'serious', icon: 'circle-x' },
  DEAD: { label: 'DLQ', tone: 'danger', icon: 'octagon-x' },
  REVOKED: { label: 'Revocado', tone: 'neutral', icon: 'ban' },
  DISABLED: { label: 'Deshabilitado', tone: 'neutral', icon: 'circle-minus' },
  SUSPENDED: { label: 'Suspendido', tone: 'danger', icon: 'circle-pause' },
  EMERGENCY_STOP: { label: 'Parada de emergencia', tone: 'danger', icon: 'octagon-alert' },
  HEALTHY: { label: 'Saludable', tone: 'ok', icon: 'heart' },
  PROGRESSING: { label: 'En progreso', tone: 'info', icon: 'loader', spin: true },
  DEGRADED: { label: 'Degradada', tone: 'danger', icon: 'heart-crack' },
  IDLE: { label: 'Suspendida', tone: 'neutral', icon: 'circle-pause' },
  UNKNOWN: { label: 'Sin actividad', tone: 'neutral', icon: 'circle-help' },
};

const FALLBACK: Omit<StatusMeta, 'label'> = { tone: 'neutral', icon: 'circle-help' };

/** Status shown as icon + label (never color alone). `plain` drops the pill for dense tiles and legends. */
@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <span class="badge" [class]="'badge tone-' + meta().tone" [class.plain]="plain() || large()" [class.large]="large()" [attr.title]="status()">
      <app-icon [name]="meta().icon" [spin]="!!meta().spin" />{{ meta().label }}
    </span>
  `,
  styles: `
    .badge {
      --icon-size: 0.875rem;
      display: inline-flex;
      align-items: center;
      gap: 0.3125rem;
      padding: 0.1875rem var(--space-2) 0.1875rem 0.375rem;
      border-radius: var(--radius-pill);
      font-size: var(--text-xs);
      font-weight: var(--weight-medium);
      line-height: 1.2;
      white-space: nowrap;
      vertical-align: middle;
    }
    .plain { padding: 0; background: none !important; color: inherit !important; }
    .plain app-icon { color: var(--tone); }
    .large { --icon-size: 1.375rem; gap: var(--space-2); font-size: var(--text-xl); font-weight: var(--weight-semibold); letter-spacing: var(--tracking-tight); }
    .tone-ok { --tone: var(--color-ok); color: var(--tone); background: var(--color-ok-soft); }
    .tone-warn { --tone: var(--color-warn); color: var(--tone); background: var(--color-warn-soft); }
    .tone-serious { --tone: var(--color-serious); color: var(--tone); background: var(--color-serious-soft); }
    .tone-danger { --tone: var(--color-danger); color: var(--tone); background: var(--color-danger-soft); }
    .tone-info { --tone: var(--color-info); color: var(--tone); background: var(--color-info-soft); }
    .tone-neutral { --tone: var(--color-neutral); color: var(--tone); background: var(--color-neutral-soft); }
  `,
})
export class StatusBadge {
  readonly status = input.required<string>();
  readonly plain = input(false);
  /** Headline size for status blocks (detail pages). */
  readonly large = input(false);
  protected readonly meta = computed(() => STATUS_META[this.status()] ?? { ...FALLBACK, label: this.status() });
}
