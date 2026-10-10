import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { Role } from '../roles';
import { authGuard, passwordChangeGuard, roleGuard } from './guards';
import { AuthService, CurrentUser } from './auth.service';

describe('guards', () => {
  let auth: AuthService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  const state = { url: '/operaciones?status=DEAD' } as RouterStateSnapshot;
  const route = {} as ActivatedRouteSnapshot;
  const run = (guard: typeof authGuard) => TestBed.runInInjectionContext(() => guard(route, state));
  const url = (result: unknown) => router.serializeUrl(result as UrlTree);

  function signedInAs(role: Role, mustChangePassword = false): void {
    const user: CurrentUser = { id: 'u1', email: 'u@x.py', role, tenantId: 't1', mustChangePassword };
    auth.user.set(user);
  }

  describe('authGuard', () => {
    it('should send an anonymous visitor to login keeping where they wanted to go', () => {
      expect(url(run(authGuard))).toBe('/login?returnUrl=%2Foperaciones%3Fstatus%3DDEAD');
    });

    it('should send a user with a pending password change to change it first', () => {
      signedInAs('platform_admin', true);
      expect(url(run(authGuard))).toBe('/cambiar-clave');
    });

    it('should let a signed-in user through', () => {
      signedInAs('viewer');
      expect(run(authGuard)).toBe(true);
    });
  });

  describe('roleGuard', () => {
    const adminsOnly = roleGuard(['platform_admin', 'tenant_admin']);

    it.each<Role>(['platform_admin', 'tenant_admin'])('should allow %s', (role) => {
      signedInAs(role);
      expect(run(adminsOnly)).toBe(true);
    });

    it.each<Role>(['operator', 'auditor', 'viewer'])('should redirect %s to the overview', (role) => {
      signedInAs(role);
      expect(url(run(adminsOnly))).toBe('/');
    });

    it('should redirect when there is no session', () => {
      expect(url(run(adminsOnly))).toBe('/');
    });
  });

  describe('passwordChangeGuard', () => {
    it('should only open the page while the change is pending', () => {
      signedInAs('tenant_admin', true);
      expect(run(passwordChangeGuard)).toBe(true);

      signedInAs('tenant_admin', false);
      expect(url(run(passwordChangeGuard))).toBe('/');
    });
  });
});
