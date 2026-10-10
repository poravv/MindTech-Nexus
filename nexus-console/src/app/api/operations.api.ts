import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { TenantScope } from '../core/tenant-scope.service';
import { Flow } from './integrations.api';

/** FAILED: rejected by the business rules of the target (final, not a platform fault). DISCARDED: a DEAD one an operator dropped. */
export const OPERATION_STATUSES = ['PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'FAILED', 'DEAD', 'DISCARDED'] as const;
export type OperationStatus = (typeof OPERATION_STATUSES)[number];

export interface Operation {
  id: string;
  tenantId: string;
  integrationId: string;
  flow: Flow;
  kind: string;
  idempotencyKey: string;
  status: OperationStatus;
  currentStep: string | null;
  attempts: number;
  maxAttempts: number;
  nextRunAt: string | null;
  correlationId: string | null;
  deadReason: string | null;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
}

export interface OperationStep {
  id: string;
  step: string;
  attempt: number;
  status: string;
  externalRef: string | null;
  responseCode: number | null;
  errorCode: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface OperationDetail extends Operation {
  steps: OperationStep[];
  result: unknown;
  lastError: unknown;
}

export interface OperationFilter {
  integrationId?: string;
  flow?: string;
  status?: string;
  from?: string;
  to?: string;
  q?: string;
  page: number;
  size: number;
}

/** Counters for the overview. */
export interface OperationSummary {
  from: string;
  byStatus: Partial<Record<OperationStatus, number>>;
  byFlow: Partial<Record<Flow, number>>;
  dead: number;
}

export type TimeseriesBucket = 'PT5M' | 'PT15M' | 'PT1H' | 'P1D';

/** Operations created in the bucket starting at `t` (UTC, epoch-aligned); absent statuses mean zero. */
export interface TimeseriesPoint {
  t: string;
  byStatus: Partial<Record<OperationStatus, number>>;
}

/** /api/admin/operations (slice S2). */
@Injectable({ providedIn: 'root' })
export class OperationsApi {
  private readonly http = inject(HttpClient);
  private readonly scope = inject(TenantScope);
  private readonly url = '/api/admin/operations';

  list(filter: OperationFilter, tenantId = this.scope.tenantId()): Observable<Operation[]> {
    return this.http.get<Operation[]>(this.url, { params: this.scope.params({ ...filter }, tenantId) });
  }

  get(id: string, tenantId = this.scope.tenantId()): Observable<OperationDetail> {
    return this.http.get<OperationDetail>(`${this.url}/${id}`, { params: this.scope.params({}, tenantId) });
  }

  /** Only DEAD operations; same idempotency key, attempts reset. */
  replay(id: string, tenantId = this.scope.tenantId()): Observable<Operation> {
    return this.http.post<Operation>(`${this.url}/${id}/replay`, {}, { params: this.scope.params({}, tenantId) });
  }

  /** Only DEAD operations: drops it from the DLQ for good (DEAD → DISCARDED). */
  discard(id: string, tenantId = this.scope.tenantId()): Observable<Operation> {
    return this.http.post<Operation>(`${this.url}/${id}/discard`, {}, { params: this.scope.params({}, tenantId) });
  }

  summary(from: string): Observable<OperationSummary> {
    return this.http.get<OperationSummary>(`${this.url}/summary`, { params: this.scope.params({ from }) });
  }

  /** Dense series (empty buckets included) from the bucket containing `from` up to now; at most 500 points. */
  timeseries(from: string, bucket: TimeseriesBucket = 'PT1H'): Observable<TimeseriesPoint[]> {
    return this.http.get<TimeseriesPoint[]>(`${this.url}/timeseries`, { params: this.scope.params({ from, bucket }) });
  }
}
