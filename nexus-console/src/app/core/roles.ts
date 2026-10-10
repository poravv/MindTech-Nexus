// Spec §5.2. The UI only hides what a role cannot do; the backend is the one that authorizes.

export type Role = 'platform_admin' | 'tenant_admin' | 'operator' | 'auditor' | 'viewer';

export const ROLE_LABELS: Record<Role, string> = {
  platform_admin: 'Administrador de plataforma',
  tenant_admin: 'Administrador de tenant',
  operator: 'Operador',
  auditor: 'Auditor',
  viewer: 'Lector',
};

export type IntegrationState = 'ENABLED' | 'PAUSED' | 'DRAINING' | 'DISABLED' | 'EMERGENCY_STOP';

const ALL: Role[] = ['platform_admin', 'tenant_admin', 'operator', 'auditor', 'viewer'];
const ADMINS: Role[] = ['platform_admin', 'tenant_admin'];
const OPERATORS: Role[] = ['platform_admin', 'tenant_admin', 'operator'];

export type Section = 'overview' | 'operations' | 'integrations' | 'connections' | 'apiClients' | 'users' | 'tenants' | 'audit';

/** Which roles may open each section of the console. */
export const SECTION_ROLES: Record<Section, readonly Role[]> = {
  overview: ALL,
  operations: ALL,
  integrations: ['platform_admin', 'tenant_admin', 'operator', 'auditor'],
  connections: ['platform_admin', 'tenant_admin', 'operator', 'auditor'],
  apiClients: ['platform_admin', 'tenant_admin', 'operator', 'auditor'],
  users: ADMINS,
  tenants: ['platform_admin'],
  audit: ['platform_admin', 'tenant_admin', 'auditor'],
};

export const canManageTenantConfig = (role: Role) => ADMINS.includes(role);
export const canReplay = (role: Role) => OPERATORS.includes(role);
export const canRunCatalogSync = (role: Role) => OPERATORS.includes(role);

/** Integration states a role may move an integration to. */
export function allowedIntegrationStates(role: Role): IntegrationState[] {
  switch (role) {
    case 'platform_admin':
      return ['ENABLED', 'PAUSED', 'DRAINING', 'DISABLED', 'EMERGENCY_STOP'];
    case 'tenant_admin':
      return ['ENABLED', 'PAUSED', 'DRAINING', 'DISABLED'];
    case 'operator':
      return ['ENABLED', 'PAUSED', 'DRAINING'];
    default:
      return [];
  }
}
