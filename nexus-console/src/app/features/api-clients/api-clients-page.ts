import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { map, Observable } from 'rxjs';
import { ApiClient, ApiClientsApi, IssuedSecret, SCOPES } from '../../api/api-clients.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { canManageTenantConfig } from '../../core/roles';
import { tenantFanOut } from '../../core/tenant-fan-out';
import { TenantScope } from '../../core/tenant-scope.service';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { FormSection } from '../../shared/ui/form-section';
import { Icon } from '../../shared/ui/icon';
import { Modal } from '../../shared/ui/modal';
import { StatusBadge } from '../../shared/ui/status-badge';

/** Base URL other pods of the cluster (the POS) use to reach the API. */
const NEXUS_IN_CLUSTER = 'http://nexus-api.nexus.svc:8080';

const SCOPE_HELP: Record<(typeof SCOPES)[number], string> = {
  'fiscal:issue': 'Emitir y anular facturas.',
  'fiscal:read': 'Consultar facturas y su KuDE.',
  'orders:write': 'Informar el estado de pedidos.',
  'orders:read': 'Consultar pedidos.',
  'catalog:read': 'Leer el catálogo.',
  'catalog:sync': 'Pedir una sincronización inmediata del catálogo.',
  'operations:read': 'Consultar operaciones y su estado.',
};

type PendingAction = { kind: 'rotate' | 'revoke'; client: ApiClient };

@Component({
  selector: 'app-api-clients-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule, FormSection, Icon, Modal, ConfirmDialog, StatusBadge],
  styles: `
    .scope-actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3); margin-top: var(--space-3); }
    .copy-row { display: flex; gap: var(--space-2); }
    .copy-row .input { flex: 1 1 auto; min-width: 0; }
    .curl { min-height: 0; resize: none; overflow-wrap: anywhere; }
    .copy-curl { justify-self: start; margin-top: var(--space-1); }
  `,
  templateUrl: './api-clients-page.html',
})
export default class ApiClientsPage {
  private readonly api = inject(ApiClientsApi);
  private readonly notifier = inject(Notifier);
  private readonly auth = inject(AuthService);
  protected readonly scope = inject(TenantScope);

  private readonly fanOut = tenantFanOut();
  /** On "Todos los tenants" every tenant's clients, with a tenant column. */
  protected readonly clients = rxResource({
    params: () => ({ tenantId: this.scope.tenantId() }),
    stream: () =>
      this.fanOut((tenantId) => this.api.list(tenantId)).pipe(
        map(({ tenants, parts }) => ({ tenantNames: new Map(tenants.map((t) => [t.id, t.name])), items: parts.flat() })),
      ),
  });
  protected readonly canManage = computed(() => {
    const role = this.auth.role();
    return role !== null && canManageTenantConfig(role);
  });
  protected readonly allScopes = SCOPES;
  protected readonly scopeHelp = SCOPE_HELP;

  protected readonly creating = signal(false);
  protected readonly name = signal('');
  protected readonly scopes = signal<Set<string>>(new Set());
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly pending = signal<PendingAction | null>(null);
  /** Kept only while the dialog is open; the API will never return it again. */
  protected readonly issued = signal<IssuedSecret | null>(null);
  protected readonly posScopes = 'fiscal:issue fiscal:read orders:read orders:write';

  /** The secret stays a shell variable: the command is safe to paste in a ticket or chat. */
  protected tokenCurl(clientId: string): string {
    return `curl -u "${clientId}:$CLIENT_SECRET" -d grant_type=client_credentials ${NEXUS_IN_CLUSTER}/oauth/token`;
  }

  protected openCreate(): void {
    this.name.set('');
    this.scopes.set(new Set());
    this.formError.set(null);
    this.creating.set(true);
  }

  protected toggleScope(scope: string, checked: boolean): void {
    this.scopes.update((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(scope);
      } else {
        next.delete(scope);
      }
      return next;
    });
  }

  protected usePosScopes(): void {
    this.scopes.set(new Set(this.posScopes.split(' ')));
  }

  protected create(): void {
    this.busy.set(true);
    this.api.create(this.name().trim(), [...this.scopes()]).subscribe({
      next: (issued) => {
        this.busy.set(false);
        this.creating.set(false);
        this.issued.set(issued);
        this.clients.reload();
      },
      error: (error) => {
        this.busy.set(false);
        this.formError.set(problemMessage(error));
      },
    });
  }

  protected confirmPending(): void {
    const action = this.pending();
    if (!action) {
      return;
    }
    this.busy.set(true);
    const request: Observable<unknown> =
      action.kind === 'rotate' ? this.api.rotate(action.client) : this.api.revoke(action.client);
    request.subscribe({
      next: (response) => {
        this.busy.set(false);
        this.pending.set(null);
        if (action.kind === 'rotate') {
          this.issued.set(response as IssuedSecret);
        } else {
          this.notifier.success(`Cliente ${action.client.name} revocado.`);
        }
        this.clients.reload();
      },
      error: (error) => {
        this.busy.set(false);
        this.pending.set(null);
        this.notifier.error(problemMessage(error));
      },
    });
  }

  protected async copy(value: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      this.notifier.success('Copiado al portapapeles.');
    } catch {
      this.notifier.error('No se pudo copiar: seleccioná el texto y copialo a mano.');
    }
  }

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la lista.');
  }
}
