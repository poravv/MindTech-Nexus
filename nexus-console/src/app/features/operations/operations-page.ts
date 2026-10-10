import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, numberAttribute, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Flow } from '../../api/integrations.api';
import { map } from 'rxjs';
import { OPERATION_STATUSES, OperationsApi } from '../../api/operations.api';
import { TenantsApi } from '../../api/tenants.api';
import { problemMessage } from '../../core/problem';
import { TenantScope } from '../../core/tenant-scope.service';
import { Pager } from '../../shared/ui/pager';
import { STATUS_META, StatusBadge } from '../../shared/ui/status-badge';
import { FLOW_META } from '../integrations/health';

const PAGE_SIZE = 25;
const FLOWS: Flow[] = ['FISCAL', 'ORDER', 'CATALOG'];

/** Filters live in the URL so a filtered view can be shared or opened from the overview. */
@Component({
  selector: 'app-operations-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule, RouterLink, Pager, StatusBadge],
  templateUrl: './operations-page.html',
})
export default class OperationsPage {
  private readonly api = inject(OperationsApi);
  private readonly router = inject(Router);
  protected readonly scope = inject(TenantScope);

  readonly integrationId = input<string>();
  readonly flow = input<string>();
  readonly status = input<string>();
  readonly from = input<string>();
  readonly to = input<string>();
  readonly q = input<string>();
  readonly page = input(0, { transform: (value: unknown) => numberAttribute(value, 0) });

  private readonly tenantsApi = inject(TenantsApi);
  /** On "Todos los tenants" the list spans tenants: names for the Tenant column, from the tenants list. */
  protected readonly tenantNames = rxResource({
    params: () => (this.scope.needsTenant() ? {} : undefined),
    stream: () => this.tenantsApi.list().pipe(map((tenants) => new Map(tenants.map((t) => [t.id, t.name])))),
    defaultValue: new Map<string, string>(),
  });
  protected readonly flows = FLOWS;
  protected readonly statuses = OPERATION_STATUSES;
  protected readonly statusMeta = STATUS_META;
  protected readonly flowMeta = FLOW_META;
  protected readonly draft = signal({ flow: '', status: '', from: '', to: '', q: '' });

  protected readonly operations = rxResource({
    params: () => ({
      tenantId: this.scope.tenantId(),
      integrationId: this.integrationId(),
      flow: this.flow(),
      status: this.status(),
      from: toIso(this.from()),
      to: toIso(this.to()),
      q: this.q(),
      page: this.page(),
      size: PAGE_SIZE,
    }),
    stream: ({ params }) => this.api.list(params),
  });
  protected readonly hasNext = computed(() => this.operations.hasValue() && this.operations.value().length === PAGE_SIZE);

  constructor() {
    effect(() => {
      this.draft.set({ flow: this.flow() ?? '', status: this.status() ?? '', from: this.from() ?? '', to: this.to() ?? '', q: this.q() ?? '' });
    });
  }

  protected update(field: keyof ReturnType<typeof this.draft>, value: string): void {
    this.draft.update((draft) => ({ ...draft, [field]: value }));
  }

  protected apply(): void {
    void this.navigate({ ...this.draft(), integrationId: this.integrationId(), page: 0 });
  }

  protected clear(): void {
    void this.navigate({ page: 0 });
  }

  protected clearIntegration(): void {
    void this.navigate({ ...this.draft(), page: 0 });
  }

  protected goTo(page: number): void {
    void this.navigate({
      integrationId: this.integrationId(),
      flow: this.flow(),
      status: this.status(),
      from: this.from(),
      to: this.to(),
      q: this.q(),
      page,
    });
  }

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudieron cargar las operaciones.');
  }

  private navigate(query: Record<string, string | number | undefined>): Promise<boolean> {
    const queryParams = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== '' && value !== undefined && value !== 0));
    return this.router.navigate([], { queryParams });
  }
}

/** datetime-local value (browser time zone) → ISO-8601 instant for the API. */
function toIso(local: string | undefined): string | undefined {
  return local ? new Date(local).toISOString() : undefined;
}
