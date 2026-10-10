import { inject } from '@angular/core';
import { catchError, forkJoin, map, Observable, of } from 'rxjs';
import { Connection, ConnectionsApi } from '../../api/connections.api';
import { Integration, IntegrationHealth, IntegrationHealthStatus, IntegrationsApi } from '../../api/integrations.api';
import { Tenant } from '../../api/tenants.api';
import { tenantFanOut } from '../../core/tenant-fan-out';

export interface IntegrationData {
  /** Tenants in view; empty for a tenant user. */
  tenants: Tenant[];
  integrations: Integration[];
  connections: Connection[];
  health: IntegrationHealth[];
}

export interface IntegrationRow {
  integration: Integration;
  tenant: Tenant | undefined;
  source: Connection | undefined;
  target: Connection | undefined;
  health: IntegrationHealthStatus;
  /** Last 24 h figures from the health endpoint; undefined when it could not be loaded. */
  insight: IntegrationHealth | undefined;
}

/**
 * Integrations, connections and 24 h health of the tenant in view, or of every tenant for a platform_admin on
 * "Todos los tenants" (must run in an injection context).
 */
export function loadIntegrationData(): () => Observable<IntegrationData> {
  const integrationsApi = inject(IntegrationsApi);
  const connectionsApi = inject(ConnectionsApi);
  const fanOut = tenantFanOut();
  const forTenant = (tenantId: string | null) =>
    forkJoin({
      integrations: integrationsApi.list(tenantId),
      connections: connectionsApi.list(tenantId),
      // Health is a bonus: without it the screens still work and every integration reads "sin actividad".
      health: integrationsApi.health(tenantId).pipe(catchError(() => of([] as IntegrationHealth[]))),
    });
  return () =>
    fanOut(forTenant).pipe(
      map(({ tenants, parts }) => ({
        tenants,
        integrations: parts.flatMap((part) => part.integrations),
        connections: parts.flatMap((part) => part.connections),
        health: parts.flatMap((part) => part.health),
      })),
    );
}

export function toRows({ tenants, integrations, connections, health }: IntegrationData): IntegrationRow[] {
  const connectionsById = new Map(connections.map((c) => [c.id, c]));
  const tenantsById = new Map(tenants.map((t) => [t.id, t]));
  const healthById = new Map(health.map((h) => [h.integrationId, h]));
  return integrations.map((integration) => {
    const insight = healthById.get(integration.id);
    return {
      integration,
      tenant: tenantsById.get(integration.tenantId),
      source: connectionsById.get(integration.sourceConnectionId),
      target: connectionsById.get(integration.targetConnectionId),
      health: insight?.health ?? 'NO_ACTIVITY',
      insight,
    };
  });
}
