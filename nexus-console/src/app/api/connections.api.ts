import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { TenantScope } from '../core/tenant-scope.service';

export type Connector = 'SIFEN' | 'POS' | 'GIFTHUB';

export interface Connection {
  id: string;
  tenantId: string;
  connector: Connector;
  name: string;
  baseUrl: string;
  settings: Record<string, unknown> | null;
  hasCredentials: boolean;
  lastTestAt: string | null;
  lastTestOk: boolean | null;
}

/** Credentials are write-only: sent on create/update, never returned. Omitting them on update keeps the stored ones. */
export interface ConnectionDraft {
  name: string;
  baseUrl: string;
  credentials?: Record<string, string>;
  settings: Record<string, unknown>;
}

export interface ConnectionTestResult {
  ok: boolean;
  detail: string;
  testedAt: string;
}

/** /api/admin/connections */
@Injectable({ providedIn: 'root' })
export class ConnectionsApi {
  private readonly http = inject(HttpClient);
  private readonly scope = inject(TenantScope);
  private readonly url = '/api/admin/connections';

  list(tenantId = this.scope.tenantId()): Observable<Connection[]> {
    return this.http.get<Connection[]>(this.url, { params: this.scope.params({}, tenantId) });
  }

  create(connector: Connector, draft: ConnectionDraft): Observable<Connection> {
    return this.http.post<Connection>(this.url, { connector, ...draft }, { params: this.scope.params() });
  }

  update(connection: Connection, draft: ConnectionDraft): Observable<Connection> {
    return this.http.put<Connection>(`${this.url}/${connection.id}`, draft, { params: this.scope.params({}, connection.tenantId) });
  }

  test(connection: Connection): Observable<ConnectionTestResult> {
    return this.http.post<ConnectionTestResult>(`${this.url}/${connection.id}/test`, {}, {
      params: this.scope.params({}, connection.tenantId),
    });
  }
}
