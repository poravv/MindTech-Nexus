import { Connection } from '../../api/connections.api';
import { IntegrationHealthStatus } from '../../api/integrations.api';
import { Operation } from '../../api/operations.api';
import { Tenant } from '../../api/tenants.api';
import { HEALTH_META, STATUS_META } from '../../shared/ui/status-badge';
import { MapAction, MapNode, MapSignal } from '../../shared/ui/tree-layout';
import { CONNECTOR_META, FLOW_META, HEALTHS } from './health';
import { IntegrationRow } from './integration-data';

export const healthSignal = (health: IntegrationHealthStatus): MapSignal => HEALTH_META[health];
export const statusSignal = (status: string): MapSignal => STATUS_META[status] ?? { icon: 'circle-help', tone: 'neutral', label: status };

/** The health that most needs attention among several (degraded beats everything). */
export function worstHealth(healths: IntegrationHealthStatus[]): IntegrationHealthStatus {
  return HEALTHS.find((health) => healths.includes(health)) ?? 'NO_ACTIVITY';
}

export function connectionSignals(connection: Connection): MapSignal[] {
  const test: MapSignal = !connection.lastTestAt
    ? { icon: 'circle-help', tone: 'neutral', label: 'Sin probar' }
    : connection.lastTestOk
      ? { icon: 'circle-check', tone: 'ok', label: 'Prueba correcta' }
      : { icon: 'circle-x', tone: 'danger', label: 'Prueba fallida' };
  const credentials: MapSignal = connection.hasCredentials
    ? { icon: 'key-round', tone: 'ok', label: 'Credenciales cargadas' }
    : { icon: 'triangle-alert', tone: 'danger', label: 'Sin credenciales' };
  return [test, credentials];
}

export function connectionNode(id: string, connection: Connection | undefined, role: 'origen' | 'destino'): MapNode {
  if (!connection) {
    return { id, icon: 'plug', kind: 'conexión', title: 'Conexión no encontrada', signals: [{ icon: 'circle-help', tone: 'neutral', label: 'Sin datos' }], note: role };
  }
  const meta = CONNECTOR_META[connection.connector];
  return {
    id,
    icon: meta.icon,
    kind: meta.label,
    title: connection.name,
    link: '/conexiones',
    signals: connectionSignals(connection),
    note: role,
    since: connection.lastTestAt ? { at: connection.lastTestAt, label: 'Última prueba' } : null,
  };
}

export function integrationNode(row: IntegrationRow, actions: MapAction[], withConnections = true): MapNode {
  const { integration, insight } = row;
  const dead = insight?.dead ?? 0;
  return {
    id: integration.id,
    icon: FLOW_META[integration.flow].icon,
    kind: FLOW_META[integration.flow].label.toLowerCase(),
    title: integration.code,
    link: ['/integraciones', integration.id],
    signals: [healthSignal(row.health), statusSignal(integration.state)],
    note: dead ? `${dead} en DLQ` : FLOW_META[integration.flow].route,
    since: insight?.lastOperationAt
      ? { at: insight.lastOperationAt, label: 'Última operación' }
      : { at: integration.updatedAt, label: 'Modificada' },
    actions,
    children: withConnections
      ? [connectionNode(`${integration.id}:source`, row.source, 'origen'), connectionNode(`${integration.id}:target`, row.target, 'destino')]
      : [],
  };
}

export function operationNode(op: Operation): MapNode {
  return {
    id: op.id,
    icon: 'activity',
    kind: 'operación',
    title: op.kind,
    link: ['/operaciones', op.id, { tenantId: op.tenantId }],
    signals: [statusSignal(op.status)],
    note: `${op.currentStep ?? 'sin paso'} · intento ${op.attempts}/${op.maxAttempts}`,
    since: { at: op.createdAt, label: 'Creada' },
  };
}

function countNote(rows: IntegrationRow[]): string {
  const degraded = rows.filter((row) => row.health === 'DEGRADED').length;
  const count = `${rows.length} ${rows.length === 1 ? 'integración' : 'integraciones'}`;
  return degraded ? `${count} · ${degraded} ${degraded === 1 ? 'falla' : 'fallan'}` : count;
}

export function tenantNode(tenant: Tenant, rows: IntegrationRow[], children: MapNode[], actions: MapAction[] = []): MapNode {
  return {
    id: tenant.id,
    icon: 'building',
    kind: 'tenant',
    title: tenant.name,
    signals: [healthSignal(worstHealth(rows.map((row) => row.health))), statusSignal(tenant.status)],
    note: countNote(rows),
    since: { at: tenant.createdAt, label: 'Alta' },
    actions,
    children,
  };
}

/** Nexus at the root, one branch per tenant (empty tenants included, so nothing looks lost). */
export function platformNode(tenants: Tenant[], rows: IntegrationRow[], children: MapNode[]): MapNode {
  return {
    id: 'nexus',
    icon: 'nexus',
    kind: 'plataforma',
    title: 'MindTech Nexus',
    signals: [healthSignal(worstHealth(rows.map((row) => row.health)))],
    note: `${tenants.length} ${tenants.length === 1 ? 'tenant' : 'tenants'} · ${rows.length} ${rows.length === 1 ? 'integración' : 'integraciones'}`,
    children,
  };
}

/** Root for a tenant user, who cannot read the tenants list. */
export function ownTenantNode(rows: IntegrationRow[], children: MapNode[]): MapNode {
  return {
    id: 'own-tenant',
    icon: 'building',
    kind: 'tenant',
    title: 'Mi organización',
    signals: [healthSignal(worstHealth(rows.map((row) => row.health)))],
    note: countNote(rows),
    children,
  };
}
