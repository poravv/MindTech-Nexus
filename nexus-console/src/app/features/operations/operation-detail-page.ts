import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { OperationsApi } from '../../api/operations.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { canReplay } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { StatusBadge } from '../../shared/ui/status-badge';

@Component({
  selector: 'app-operation-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, JsonPipe, RouterLink, ConfirmDialog, StatusBadge],
  templateUrl: './operation-detail-page.html',
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

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la operación.');
  }
}
