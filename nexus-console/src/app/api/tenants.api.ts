import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

export interface Tenant {
  id: string;
  slug: string;
  name: string;
  status: 'ACTIVE' | 'SUSPENDED';
  createdAt: string;
}

/** /api/admin/tenants — platform_admin only. */
@Injectable({ providedIn: 'root' })
export class TenantsApi {
  private readonly http = inject(HttpClient);
  private readonly url = '/api/admin/tenants';
  /** Bumped on every successful create so the tenant picker reloads. */
  readonly changes = signal(0);

  list(): Observable<Tenant[]> {
    return this.http.get<Tenant[]>(this.url);
  }

  create(slug: string, name: string): Observable<Tenant> {
    return this.http.post<Tenant>(this.url, { slug, name }).pipe(tap(() => this.changes.update((n) => n + 1)));
  }
}
