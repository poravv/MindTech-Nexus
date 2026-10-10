import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { AuditApi } from '../../api/audit.api';
import { problemMessage } from '../../core/problem';
import { TenantScope } from '../../core/tenant-scope.service';
import { Pager } from '../../shared/ui/pager';

const PAGE_SIZE = 50;

@Component({
  selector: 'app-audit-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, JsonPipe, Pager],
  templateUrl: './audit-page.html',
})
export default class AuditPage {
  private readonly api = inject(AuditApi);
  private readonly scope = inject(TenantScope);

  protected readonly page = signal(0);
  protected readonly events = rxResource({
    params: () => ({ tenantId: this.scope.tenantId(), page: this.page() }),
    stream: ({ params }) => this.api.list(params.page, PAGE_SIZE),
  });
  protected readonly hasNext = computed(() => this.events.hasValue() && this.events.value().length === PAGE_SIZE);

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la auditoría.');
  }
}
