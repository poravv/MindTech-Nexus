import { inject } from '@angular/core';
import { catchError, forkJoin, Observable, of } from 'rxjs';
import { Connection, ConnectionsApi } from '../../api/connections.api';
import { Integration, IntegrationsApi } from '../../api/integrations.api';
import { Operation, OperationsApi } from '../../api/operations.api';
import { Health, HEALTH_SAMPLE_SIZE, HEALTH_WINDOW_MS, integrationHealth } from './health';

export interface IntegrationData {
  integrations: Integration[];
  connections: Connection[];
  operations: Operation[];
}

export interface IntegrationRow {
  integration: Integration;
  source: Connection | undefined;
  target: Connection | undefined;
  health: Health;
  dead: number;
  /** Recent operations of this integration, newest first. */
  operations: Operation[];
  /** Most recent DLQ/failed operation: where and why it broke. */
  failing: Operation | undefined;
}

/**
 * Integrations, connections and the last 24 h of operations of the current tenant (must run in an injection context).
 * The admin API has no per-integration health yet, so it is derived from a sample of up to 200 operations.
 */
export function loadIntegrationData(): () => Observable<IntegrationData> {
  const integrationsApi = inject(IntegrationsApi);
  const connectionsApi = inject(ConnectionsApi);
  const operationsApi = inject(OperationsApi);
  return () =>
    forkJoin({
      integrations: integrationsApi.list(),
      connections: connectionsApi.list(),
      // Health is a bonus: without the operations sample the screens still work and health reads "unknown".
      operations: operationsApi
        .list({ from: new Date(Date.now() - HEALTH_WINDOW_MS).toISOString(), page: 0, size: HEALTH_SAMPLE_SIZE })
        .pipe(catchError(() => of([] as Operation[]))),
    });
}

export function toRows({ integrations, connections, operations }: IntegrationData): IntegrationRow[] {
  const byId = new Map(connections.map((c) => [c.id, c]));
  return integrations.map((integration) => {
    const own = operations.filter((op) => op.integrationId === integration.id);
    return {
      integration,
      source: byId.get(integration.sourceConnectionId),
      target: byId.get(integration.targetConnectionId),
      health: integrationHealth(integration, operations),
      dead: own.filter((op) => op.status === 'DEAD').length,
      operations: own,
      failing: own.find((op) => op.status === 'DEAD' || op.status === 'FAILED'),
    };
  });
}
