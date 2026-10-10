import { HttpParams } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { AuthService } from './auth/auth.service';

const STORAGE_KEY = 'nexus.tenant';

type ParamValue = string | number | null | undefined;

/**
 * Tenant the console is looking at. Tenant users are pinned to the tenant of their token;
 * a platform_admin chooses one and it travels as ?tenantId= (ADR-005).
 */
@Injectable({ providedIn: 'root' })
export class TenantScope {
  private readonly auth = inject(AuthService);
  private readonly selected = signal<string | null>(readStored());

  readonly isPlatformAdmin = computed(() => this.auth.role() === 'platform_admin');
  readonly tenantId = computed(() => (this.isPlatformAdmin() ? this.selected() : null));
  /** A platform_admin without a chosen tenant cannot open tenant-scoped screens. */
  readonly needsTenant = computed(() => this.isPlatformAdmin() && !this.selected());

  select(tenantId: string | null): void {
    this.selected.set(tenantId);
    try {
      if (tenantId) {
        sessionStorage.setItem(STORAGE_KEY, tenantId);
      } else {
        sessionStorage.removeItem(STORAGE_KEY);
      }
    } catch {
      // Storage can be unavailable (private mode); the choice still lives for this tab.
    }
  }

  /**
   * Query params with the tenant plus the given non-empty values. `tenantId` overrides the selected one for a
   * platform_admin acting on a row of another tenant while looking at "Todos los tenants".
   */
  params(extra: Record<string, ParamValue> = {}, tenantId: string | null = this.tenantId()): HttpParams {
    let params = new HttpParams();
    if (tenantId && this.isPlatformAdmin()) {
      params = params.set('tenantId', tenantId);
    }
    for (const [key, value] of Object.entries(extra)) {
      if (value !== null && value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return params;
  }
}

function readStored(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}
