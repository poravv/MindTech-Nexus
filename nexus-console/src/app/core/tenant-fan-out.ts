import { inject } from '@angular/core';
import { forkJoin, map, Observable, of, switchMap } from 'rxjs';
import { Tenant, TenantsApi } from '../api/tenants.api';
import { TenantScope } from './tenant-scope.service';

export interface FanOut<T> {
  /** Tenants in view (platform_admin only; a tenant user gets an empty list). */
  tenants: Tenant[];
  /** One result per tenant in view. */
  parts: T[];
}

/**
 * Runs a tenant-scoped request for the tenant in view, or once per tenant when a platform_admin looks at
 * "Todos los tenants" (the admin API requires ?tenantId=). Must be created in an injection context.
 */
export function tenantFanOut(): <T>(fetch: (tenantId: string | null) => Observable<T>) => Observable<FanOut<T>> {
  const scope = inject(TenantScope);
  const tenantsApi = inject(TenantsApi);
  return <T>(fetch: (tenantId: string | null) => Observable<T>) => {
    if (!scope.isPlatformAdmin()) {
      return fetch(null).pipe(map((part) => ({ tenants: [] as Tenant[], parts: [part] })));
    }
    const selected = scope.tenantId();
    // ponytail: with "Todos los tenants" this is N requests per call. Move to an aggregated admin endpoint once
    // tenants pass a few dozen.
    return tenantsApi.list().pipe(
      map((tenants) => (selected ? tenants.filter((tenant) => tenant.id === selected) : tenants)),
      switchMap((tenants) =>
        (tenants.length ? forkJoin(tenants.map((tenant) => fetch(tenant.id))) : of([] as T[])).pipe(
          map((parts) => ({ tenants, parts })),
        ),
      ),
    );
  };
}
