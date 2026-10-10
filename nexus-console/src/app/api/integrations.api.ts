import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { IntegrationState } from '../core/roles';
import { TenantScope } from '../core/tenant-scope.service';
import { OperationStatus } from './operations.api';

export type Flow = 'FISCAL' | 'ORDER' | 'CATALOG';

/** Spec §6 `policy` jsonb; scheduleEverySeconds only applies to CATALOG. */
export interface IntegrationPolicy {
  ratePerSecond: number;
  burst: number;
  maxInFlight: number;
  maxAttempts: number;
  baseDelaySeconds: number;
  maxDelaySeconds: number;
  readTimeoutMs: number;
  scheduleEverySeconds?: number;
}

export interface Integration {
  id: string;
  tenantId: string;
  flow: Flow;
  code: string;
  sourceConnectionId: string;
  targetConnectionId: string;
  state: IntegrationState;
  policy: IntegrationPolicy;
  version: number;
  updatedAt: string;
}

export interface IntegrationDraft {
  flow: Flow;
  code: string;
  sourceConnectionId: string;
  targetConnectionId: string;
  policy: IntegrationPolicy;
}

export type IntegrationHealthStatus = 'HEALTHY' | 'PROGRESSING' | 'DEGRADED' | 'SUSPENDED' | 'NO_ACTIVITY';

/**
 * GET /api/admin/integrations/health, one per integration, over the operations created since `from`.
 * `dead` is the integration's whole current DLQ, regardless of age.
 */
export interface IntegrationHealth {
  integrationId: string;
  health: IntegrationHealthStatus;
  byStatus: Partial<Record<OperationStatus, number>>;
  dead: number;
  lastOperationAt: string | null;
  lastFailure: {
    operationId: string;
    status: 'FAILED' | 'DEAD';
    step: string | null;
    errorCode: string | null;
    at: string;
  } | null;
}

/** /api/admin/integrations (slice S2). */
@Injectable({ providedIn: 'root' })
export class IntegrationsApi {
  private readonly http = inject(HttpClient);
  private readonly scope = inject(TenantScope);
  private readonly url = '/api/admin/integrations';

  list(tenantId = this.scope.tenantId()): Observable<Integration[]> {
    return this.http.get<Integration[]>(this.url, { params: this.scope.params({}, tenantId) });
  }

  /** `from` defaults to the last 24 h on the server. A platform_admin must name the tenant. */
  health(tenantId = this.scope.tenantId(), from?: string): Observable<IntegrationHealth[]> {
    return this.http.get<IntegrationHealth[]>(`${this.url}/health`, { params: this.scope.params({ from }, tenantId) });
  }

  create(draft: IntegrationDraft, tenantId = this.scope.tenantId()): Observable<Integration> {
    return this.http.post<Integration>(this.url, draft, { params: this.scope.params({}, tenantId) });
  }

  update(integration: Integration, draft: IntegrationDraft): Observable<Integration> {
    return this.http.put<Integration>(`${this.url}/${integration.id}`, draft, { params: this.scope.params({}, integration.tenantId) });
  }

  changeState(integration: Integration, state: IntegrationState, reason: string): Observable<Integration> {
    return this.http.post<Integration>(`${this.url}/${integration.id}/state`, { state, reason }, {
      params: this.scope.params({}, integration.tenantId),
    });
  }

  /** CATALOG only: runs a sync now. */
  run(integration: Integration): Observable<unknown> {
    return this.http.post(`${this.url}/${integration.id}/run`, {}, { params: this.scope.params({}, integration.tenantId) });
  }
}
