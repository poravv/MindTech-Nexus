import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { TenantScope } from '../core/tenant-scope.service';

export interface AuditEvent {
  id: string;
  tenantId: string | null;
  actorType: 'USER' | 'CLIENT' | 'SYSTEM';
  actorId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  correlationId: string | null;
  happenedAt: string;
}

/** /api/admin/audit — newest first. */
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly http = inject(HttpClient);
  private readonly scope = inject(TenantScope);

  list(page: number, size: number): Observable<AuditEvent[]> {
    return this.http.get<AuditEvent[]>('/api/admin/audit', { params: this.scope.params({ page, size }) });
  }
}
