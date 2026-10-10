import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role } from '../roles';
import { AuthService } from './auth.service';

/** Requires a session; a user who still must change the bootstrap password is sent to do it first. */
export const authGuard: CanActivateFn = (_route, state) => {
  const user = inject(AuthService).user();
  const router = inject(Router);
  if (!user) {
    return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  }
  return user.mustChangePassword ? router.createUrlTree(['/cambiar-clave']) : true;
};

/** Lets the route through only for the given roles; others land on the overview. */
export function roleGuard(roles: readonly Role[]): CanActivateFn {
  return () => {
    const role = inject(AuthService).role();
    return role !== null && roles.includes(role) ? true : inject(Router).createUrlTree(['/']);
  };
}

/** Only for a logged-in user that has to change the password. */
export const passwordChangeGuard: CanActivateFn = () => {
  const user = inject(AuthService).user();
  if (!user) {
    return inject(Router).createUrlTree(['/login']);
  }
  return user.mustChangePassword ? true : inject(Router).createUrlTree(['/']);
};
