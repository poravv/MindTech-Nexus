import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { TenantScope } from '../core/tenant-scope.service';

export const SCOPES = ['fiscal:issue', 'fiscal:read', 'orders:write', 'orders:read', 'catalog:read', 'catalog:sync', 'operations:read'] as const;

export interface ApiClient {
  id: string;
  tenantId: string;
  clientId: string;
  name: string;
  scopes: string[];
  status: 'ACTIVE' | 'REVOKED';
  secretRotatedAt: string | null;
  lastUsedAt: string | null;
}

/** Returned only by create and rotate: the one and only time the secret is visible. */
export interface IssuedSecret {
  client: ApiClient;
  clientSecret: string;
}

/** /api/admin/api-clients */
@Injectable({ providedIn: 'root' })
export class ApiClientsApi {
  private readonly http = inject(HttpClient);
  private readonly scope = inject(TenantScope);
  private readonly url = '/api/admin/api-clients';

  list(tenantId = this.scope.tenantId()): Observable<ApiClient[]> {
    return this.http.get<ApiClient[]>(this.url, { params: this.scope.params({}, tenantId) });
  }

  create(name: string, scopes: string[]): Observable<IssuedSecret> {
    return this.http.post<IssuedSecret>(this.url, { name, scopes }, { params: this.scope.params() });
  }

  rotate(client: ApiClient): Observable<IssuedSecret> {
    return this.http.post<IssuedSecret>(`${this.url}/${client.id}/rotate`, {}, { params: this.scope.params({}, client.tenantId) });
  }

  revoke(client: ApiClient): Observable<ApiClient> {
    return this.http.post<ApiClient>(`${this.url}/${client.id}/revoke`, {}, { params: this.scope.params({}, client.tenantId) });
  }
}
