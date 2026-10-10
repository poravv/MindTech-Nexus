import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Connection, ConnectionDraft, ConnectionsApi, Connector } from '../../api/connections.api';
import { AuthService } from '../../core/auth/auth.service';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { canManageTenantConfig } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { Modal } from '../../shared/ui/modal';
import { TenantRequired } from '../../shared/ui/tenant-required';

interface FieldSpec {
  key: string;
  label: string;
  hint?: string;
}

/** Credential keys and settings each connector expects (spec §5.3 and §17). */
const CONNECTORS: Record<Connector, { label: string; credentials: FieldSpec[]; settings: FieldSpec[]; flags: FieldSpec[] }> = {
  SIFEN: {
    label: 'SIFEN (facturación electrónica)',
    credentials: [
      { key: 'accessKey', label: 'Access key' },
      { key: 'secretKey', label: 'Secret key' },
    ],
    settings: [],
    flags: [],
  },
  POS: {
    label: 'Punto de venta',
    credentials: [{ key: 'apiKey', label: 'API key', hint: 'Formato pv_live_…' }],
    settings: [
      { key: 'branchRef', label: 'Sucursal para pedidos' },
      { key: 'warehouseRef', label: 'Depósito para pedidos' },
      { key: 'defaultCustomerId', label: 'Cliente por defecto', hint: 'Se usa si el pedido no trae cliente.' },
    ],
    flags: [],
  },
  GIFTHUB: {
    label: 'GiftHub',
    credentials: [{ key: 'apiKey', label: 'API key', hint: 'Formato gh_live_…' }],
    settings: [],
    flags: [{ key: 'dryRun', label: 'Modo prueba (dry run) en la sincronización de catálogo' }],
  },
};

interface ConnectionForm {
  connector: Connector;
  name: string;
  baseUrl: string;
  credentials: Record<string, string>;
  settings: Record<string, string>;
  flags: Record<string, boolean>;
}

@Component({
  selector: 'app-connections-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule, Modal, TenantRequired],
  templateUrl: './connections-page.html',
})
export default class ConnectionsPage {
  private readonly api = inject(ConnectionsApi);
  private readonly notifier = inject(Notifier);
  private readonly auth = inject(AuthService);
  protected readonly scope = inject(TenantScope);

  protected readonly connections = rxResource({
    params: () => (this.scope.needsTenant() ? undefined : { tenantId: this.scope.tenantId() }),
    stream: () => this.api.list(),
  });
  protected readonly canManage = computed(() => {
    const role = this.auth.role();
    return role !== null && canManageTenantConfig(role);
  });
  protected readonly connectors = CONNECTORS;
  protected readonly connectorTypes = Object.keys(CONNECTORS) as Connector[];

  /** null: closed; undefined id: creating. */
  protected readonly editing = signal<{ id?: string; original?: Connection } | null>(null);
  protected form: ConnectionForm = emptyForm('POS');
  protected readonly busy = signal(false);
  protected readonly testing = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);

  protected openCreate(): void {
    this.form = emptyForm('POS');
    this.formError.set(null);
    this.editing.set({});
  }

  protected openEdit(connection: Connection): void {
    const settings = connection.settings ?? {};
    const spec = CONNECTORS[connection.connector];
    this.form = {
      ...emptyForm(connection.connector),
      name: connection.name,
      baseUrl: connection.baseUrl,
      settings: Object.fromEntries(spec.settings.map((f) => [f.key, String(settings[f.key] ?? '')])),
      flags: Object.fromEntries(spec.flags.map((f) => [f.key, settings[f.key] === true])),
    };
    this.formError.set(null);
    this.editing.set({ id: connection.id, original: connection });
  }

  /** Drops whatever credentials were typed when the dialog closes without saving. */
  protected close(): void {
    this.editing.set(null);
    this.form = emptyForm('POS');
  }

  protected changeConnector(connector: Connector): void {
    this.form = { ...emptyForm(connector), name: this.form.name, baseUrl: this.form.baseUrl };
  }

  protected save(): void {
    const target = this.editing();
    if (!target) {
      return;
    }
    const credentials = Object.values(this.form.credentials).some((v) => v.trim()) ? this.form.credentials : undefined;
    if (credentials && Object.values(credentials).some((v) => !v.trim())) {
      this.formError.set('Completá todas las credenciales o dejalas todas vacías.');
      return;
    }
    const draft: ConnectionDraft = {
      name: this.form.name.trim(),
      baseUrl: this.form.baseUrl.trim(),
      credentials,
      settings: mergeSettings(target.original?.settings ?? {}, this.form),
    };
    this.busy.set(true);
    const request = target.id ? this.api.update(target.id, draft) : this.api.create(this.form.connector, draft);
    request.subscribe({
      next: (connection) => {
        this.busy.set(false);
        this.close();
        this.notifier.success(`Conexión ${connection.name} guardada.`);
        this.connections.reload();
      },
      error: (error) => {
        this.busy.set(false);
        this.formError.set(problemMessage(error));
      },
    });
  }

  protected test(connection: Connection): void {
    this.testing.set(connection.id);
    this.api.test(connection.id).subscribe({
      next: (result) => {
        this.testing.set(null);
        const message = `${connection.name}: ${result.ok ? 'conexión correcta' : 'falló'}${result.detail ? ` — ${result.detail}` : ''}`;
        if (result.ok) {
          this.notifier.success(message);
        } else {
          this.notifier.error(message);
        }
        this.connections.reload();
      },
      error: (error) => {
        this.testing.set(null);
        this.notifier.error(problemMessage(error));
      },
    });
  }

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la lista.');
  }
}

function emptyForm(connector: Connector): ConnectionForm {
  const spec = CONNECTORS[connector];
  return {
    connector,
    name: '',
    baseUrl: '',
    credentials: Object.fromEntries(spec.credentials.map((f) => [f.key, ''])),
    settings: Object.fromEntries(spec.settings.map((f) => [f.key, ''])),
    flags: Object.fromEntries(spec.flags.map((f) => [f.key, false])),
  };
}

/** Keeps settings the form does not know about; an emptied text field removes its key. */
function mergeSettings(original: Record<string, unknown>, form: ConnectionForm): Record<string, unknown> {
  const merged: Record<string, unknown> = { ...original, ...form.flags };
  for (const [key, value] of Object.entries(form.settings)) {
    if (value.trim()) {
      merged[key] = value.trim();
    } else {
      delete merged[key];
    }
  }
  return merged;
}
