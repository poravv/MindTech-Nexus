import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Flow } from '../../api/integrations.api';
import { OperationsApi, OperationStep } from '../../api/operations.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { canReplay } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { Icon } from '../../shared/ui/icon';
import { StatusBadge, statusMeta } from '../../shared/ui/status-badge';
import { FLOW_META } from '../integrations/health';

/** Fields of lastError the console shows; anything else the connector attached stays out of the screen. */
interface SafeError {
  kind?: string;
  code?: string;
  httpStatus?: number;
  detail?: string;
}

@Component({
  selector: 'app-operation-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, JsonPipe, RouterLink, ConfirmDialog, Icon, StatusBadge],
  templateUrl: './operation-detail-page.html',
  styleUrl: './operation-detail-page.scss',
})
export default class OperationDetailPage {
  private readonly api = inject(OperationsApi);
  private readonly auth = inject(AuthService);
  private readonly notifier = inject(Notifier);
  private readonly scope = inject(TenantScope);

  readonly id = input.required<string>();
  /** Matrix param set by links from "Todos los tenants", where the scope alone can't tell the API whose operation it is. */
  readonly tenantId = input<string>();

  private readonly tenant = computed(() => this.tenantId() ?? this.scope.tenantId());
  protected readonly operation = rxResource({
    params: () => ({ id: this.id(), tenantId: this.tenant() }),
    stream: ({ params }) => this.api.get(params.id, params.tenantId),
  });
  protected readonly canReplay = computed(() => {
    const role = this.auth.role();
    return role !== null && canReplay(role) && this.operation.hasValue() && this.operation.value().status === 'DEAD';
  });
  protected readonly lastError = computed<SafeError | null>(() => {
    const raw = this.operation.hasValue() ? this.operation.value().lastError : null;
    if (!raw || typeof raw !== 'object') {
      return null;
    }
    const { kind, code, httpStatus, detail } = raw as Record<string, unknown>;
    return {
      kind: typeof kind === 'string' ? kind : undefined,
      code: typeof code === 'string' ? code : undefined,
      httpStatus: typeof httpStatus === 'number' ? httpStatus : undefined,
      detail: typeof detail === 'string' ? detail : undefined,
    };
  });
  /** nextRunAt stays on the row after the operation ends; it only means something while the operation waits. */
  protected readonly nextRun = computed(() => {
    const op = this.operation.hasValue() ? this.operation.value() : null;
    return op && (op.status === 'PENDING' || op.status === 'RETRY') ? op.nextRunAt : null;
  });
  protected readonly pending = signal<'replay' | 'discard' | null>(null);
  protected readonly busy = signal(false);

  protected replay(): void {
    this.busy.set(true);
    this.api.replay(this.id(), this.tenant()).subscribe({
      next: () => {
        this.busy.set(false);
        this.pending.set(null);
        this.notifier.success('Operación reencolada con la misma clave de idempotencia.');
        this.operation.reload();
      },
      error: (error) => {
        this.busy.set(false);
        this.pending.set(null);
        this.notifier.error(problemMessage(error));
      },
    });
  }

  protected discard(): void {
    this.busy.set(true);
    this.api.discard(this.id(), this.tenant()).subscribe({
      next: () => {
        this.busy.set(false);
        this.pending.set(null);
        this.notifier.success('Operación descartada.');
        this.operation.reload();
      },
      error: (error) => {
        this.busy.set(false);
        this.pending.set(null);
        this.notifier.error(problemMessage(error, 'No se pudo descartar la operación.'));
      },
    });
  }

  protected readonly statusMeta = statusMeta;

  protected flowLabel(flow: Flow): string {
    return FLOW_META[flow]?.label ?? flow;
  }

  protected duration(step: OperationStep): string | null {
    if (!step.finishedAt) {
      return null;
    }
    const ms = new Date(step.finishedAt).getTime() - new Date(step.startedAt).getTime();
    return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
  }

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la operación.');
  }
}
