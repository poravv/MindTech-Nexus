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
import { StatusBadge } from '../../shared/ui/status-badge';
import { FLOW_META } from '../integrations/health';

/** Fields of lastError the console shows; anything else the connector attached stays out of the screen. */
interface SafeError {
  kind?: string;
  code?: string;
  httpStatus?: number;
  message?: string;
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

  protected readonly operation = rxResource({
    params: () => ({ id: this.id(), tenantId: this.scope.tenantId() }),
    stream: ({ params }) => this.api.get(params.id),
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
    const { kind, code, httpStatus, message } = raw as Record<string, unknown>;
    return {
      kind: typeof kind === 'string' ? kind : undefined,
      code: typeof code === 'string' ? code : undefined,
      httpStatus: typeof httpStatus === 'number' ? httpStatus : undefined,
      message: typeof message === 'string' ? message : undefined,
    };
  });
  protected readonly confirming = signal(false);
  protected readonly busy = signal(false);

  protected replay(): void {
    this.busy.set(true);
    this.api.replay(this.id()).subscribe({
      next: () => {
        this.busy.set(false);
        this.confirming.set(false);
        this.notifier.success('Operación reencolada con la misma clave de idempotencia.');
        this.operation.reload();
      },
      error: (error) => {
        this.busy.set(false);
        this.confirming.set(false);
        this.notifier.error(problemMessage(error));
      },
    });
  }

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
