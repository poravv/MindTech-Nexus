import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Connection } from '../../api/connections.api';
import { Integration, IntegrationsApi } from '../../api/integrations.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { allowedIntegrationStates, canRunCatalogSync } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { Icon } from '../../shared/ui/icon';
import { StatusBadge } from '../../shared/ui/status-badge';
import { TenantRequired } from '../../shared/ui/tenant-required';
import { CONNECTOR_ICONS, FLOW_META } from './health';
import { loadIntegrationData, toRows } from './integration-data';
import { IntegrationStateDialog } from './state-dialog';

const RECENT_NODES = 6;

const POLICY_LABELS: [keyof Integration['policy'], string][] = [
  ['ratePerSecond', 'Operaciones por segundo'],
  ['burst', 'Ráfaga'],
  ['maxInFlight', 'Máx. en curso'],
  ['maxAttempts', 'Máx. intentos'],
  ['baseDelaySeconds', 'Espera base (s)'],
  ['maxDelaySeconds', 'Espera máxima (s)'],
  ['readTimeoutMs', 'Timeout de lectura (ms)'],
  ['scheduleEverySeconds', 'Sincronizar cada (s)'],
];

/** One integration as a topology: source system → Nexus → target system, plus its recent operations. */
@Component({
  selector: 'app-integration-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, NgTemplateOutlet, RouterLink, Icon, StatusBadge, TenantRequired, IntegrationStateDialog],
  templateUrl: './integration-detail-page.html',
  styleUrl: './integration-detail-page.scss',
})
export default class IntegrationDetailPage {
  private readonly api = inject(IntegrationsApi);
  private readonly auth = inject(AuthService);
  private readonly notifier = inject(Notifier);
  protected readonly scope = inject(TenantScope);

  readonly id = input.required<string>();

  private readonly load = loadIntegrationData();
  protected readonly data = rxResource({
    params: () => (this.scope.needsTenant() ? undefined : { tenantId: this.scope.tenantId() }),
    stream: () => this.load(),
  });
  protected readonly row = computed(() =>
    this.data.hasValue() ? toRows(this.data.value()).find((row) => row.integration.id === this.id()) : undefined,
  );
  protected readonly recent = computed(() => this.row()?.operations.slice(0, RECENT_NODES) ?? []);
  protected readonly policy = computed(() => {
    const policy = this.row()?.integration.policy;
    return policy ? POLICY_LABELS.filter(([key]) => policy[key] !== undefined).map(([key, label]) => ({ label, value: policy[key] })) : [];
  });
  /** Why the health is what it is, in the operator's words. */
  protected readonly healthReason = computed(() => {
    const row = this.row();
    if (!row) {
      return '';
    }
    const failed = row.operations.filter((op) => op.status === 'FAILED').length;
    switch (row.health) {
      case 'DEGRADED':
        return [row.dead && `${row.dead} en DLQ`, failed && `${failed} fallidas`].filter(Boolean).join(' · ') + ' en 24 h';
      case 'PROGRESSING':
        return row.integration.state === 'DRAINING' ? 'Terminando las operaciones en curso' : 'Hay operaciones en curso o reintentando';
      case 'HEALTHY':
        return `${row.operations.length} operaciones exitosas en 24 h`;
      case 'IDLE':
        return 'La integración no está despachando';
      default:
        return 'Sin operaciones en las últimas 24 h';
    }
  });

  protected readonly canChangeState = computed(() => {
    const role = this.auth.role();
    return role !== null && allowedIntegrationStates(role).length > 0;
  });
  protected readonly canRun = computed(() => {
    const role = this.auth.role();
    return role !== null && canRunCatalogSync(role);
  });
  protected readonly stateTarget = signal<Integration | null>(null);

  protected readonly flowMeta = FLOW_META;

  protected iconOf(connection: Connection) {
    return CONNECTOR_ICONS[connection.connector];
  }

  protected testStatus(connection: Connection): { icon: 'circle-check' | 'circle-x' | 'circle-help'; tone: string; label: string } {
    if (!connection.lastTestAt) {
      return { icon: 'circle-help', tone: 'neutral', label: 'Sin probar' };
    }
    return connection.lastTestOk
      ? { icon: 'circle-check', tone: 'ok', label: 'Prueba correcta' }
      : { icon: 'circle-x', tone: 'danger', label: 'Prueba fallida' };
  }

  protected host(url: string): string {
    try {
      return new URL(url).host;
    } catch {
      return url;
    }
  }

  protected stateChanged(): void {
    this.stateTarget.set(null);
    this.data.reload();
  }

  protected run(integration: Integration): void {
    this.api.run(integration.id).subscribe({
      next: () => this.notifier.success(`Sincronización de ${integration.code} encolada.`),
      error: (error) => this.notifier.error(problemMessage(error)),
    });
  }

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la integración.');
  }
}
