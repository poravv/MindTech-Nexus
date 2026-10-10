import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

type Tone = 'ok' | 'warn' | 'danger' | 'info' | 'neutral';

const TONES: Record<string, Tone> = {
  ACTIVE: 'ok',
  ENABLED: 'ok',
  SUCCEEDED: 'ok',
  PENDING: 'info',
  RUNNING: 'info',
  RETRY: 'warn',
  PAUSED: 'warn',
  DRAINING: 'warn',
  FAILED: 'danger',
  DEAD: 'danger',
  REVOKED: 'neutral',
  DISABLED: 'neutral',
  SUSPENDED: 'danger',
  EMERGENCY_STOP: 'danger',
};

const LABELS: Record<string, string> = {
  ACTIVE: 'Activo',
  ENABLED: 'Habilitada',
  SUCCEEDED: 'Exitosa',
  PENDING: 'Pendiente',
  RUNNING: 'En curso',
  RETRY: 'Reintento',
  PAUSED: 'Pausada',
  DRAINING: 'Drenando',
  FAILED: 'Fallida',
  DEAD: 'DLQ',
  REVOKED: 'Revocado',
  DISABLED: 'Deshabilitado',
  SUSPENDED: 'Suspendido',
  EMERGENCY_STOP: 'Parada de emergencia',
};

/** Colored status label for tenants, users, clients, integrations and operations. */
@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge" [class]="'badge tone-' + tone()" [attr.title]="status()">{{ label() }}</span>`,
  styles: `
    .badge {
      display: inline-flex;
      align-items: center;
      gap: var(--space-1);
      padding: 0.125rem var(--space-2);
      border-radius: var(--radius-pill);
      font-size: var(--text-xs);
      font-weight: var(--weight-medium);
      white-space: nowrap;
    }
    .badge::before {
      content: '';
      width: 0.45rem;
      height: 0.45rem;
      border-radius: 50%;
      background: currentColor;
    }
    .tone-ok { color: var(--color-ok); background: var(--color-ok-soft); }
    .tone-warn { color: var(--color-warn); background: var(--color-warn-soft); }
    .tone-danger { color: var(--color-danger); background: var(--color-danger-soft); }
    .tone-info { color: var(--color-info); background: var(--color-info-soft); }
    .tone-neutral { color: var(--color-neutral); background: var(--color-neutral-soft); }
  `,
})
export class StatusBadge {
  readonly status = input.required<string>();
  protected readonly tone = computed(() => TONES[this.status()] ?? 'neutral');
  protected readonly label = computed(() => LABELS[this.status()] ?? this.status());
}
