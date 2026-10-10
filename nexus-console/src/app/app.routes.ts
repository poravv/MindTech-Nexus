import { Routes } from '@angular/router';
import { authGuard, passwordChangeGuard, roleGuard } from './core/auth/guards';
import { SECTION_ROLES } from './core/roles';

export const routes: Routes = [
  { path: 'login', title: 'Ingresar · Nexus', loadComponent: () => import('./features/login/login-page') },
  {
    path: 'cambiar-clave',
    title: 'Cambiar contraseña · Nexus',
    canActivate: [passwordChangeGuard],
    loadComponent: () => import('./features/change-password/change-password-page'),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/shell/shell'),
    children: [
      { path: '', title: 'Resumen · Nexus', loadComponent: () => import('./features/overview/overview-page') },
      {
        path: 'operaciones',
        title: 'Operaciones · Nexus',
        canActivate: [roleGuard(SECTION_ROLES.operations)],
        loadComponent: () => import('./features/operations/operations-page'),
      },
      {
        path: 'operaciones/:id',
        title: 'Detalle de operación · Nexus',
        canActivate: [roleGuard(SECTION_ROLES.operations)],
        loadComponent: () => import('./features/operations/operation-detail-page'),
      },
      {
        path: 'integraciones',
        title: 'Integraciones · Nexus',
        canActivate: [roleGuard(SECTION_ROLES.integrations)],
        loadComponent: () => import('./features/integrations/integrations-page'),
      },
      {
        path: 'integraciones/:id',
        title: 'Detalle de integración · Nexus',
        canActivate: [roleGuard(SECTION_ROLES.integrations)],
        loadComponent: () => import('./features/integrations/integration-detail-page'),
      },
      {
        path: 'conexiones',
        title: 'Conexiones · Nexus',
        canActivate: [roleGuard(SECTION_ROLES.connections)],
        loadComponent: () => import('./features/connections/connections-page'),
      },
      {
        path: 'clientes',
        title: 'Clientes m2m · Nexus',
        canActivate: [roleGuard(SECTION_ROLES.apiClients)],
        loadComponent: () => import('./features/api-clients/api-clients-page'),
      },
      {
        path: 'usuarios',
        title: 'Usuarios · Nexus',
        canActivate: [roleGuard(SECTION_ROLES.users)],
        loadComponent: () => import('./features/users/users-page'),
      },
      {
        path: 'tenants',
        title: 'Tenants · Nexus',
        canActivate: [roleGuard(SECTION_ROLES.tenants)],
        loadComponent: () => import('./features/tenants/tenants-page'),
      },
      {
        path: 'auditoria',
        title: 'Auditoría · Nexus',
        canActivate: [roleGuard(SECTION_ROLES.audit)],
        loadComponent: () => import('./features/audit/audit-page'),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
