import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AUTH_URL, AuthService } from './auth.service';

const SESSION_ENDPOINTS = ['login', 'refresh', 'logout'].map((name) => `${AUTH_URL}/${name}`);

/** Adds the Bearer token to admin API calls and, on a 401, refreshes once and retries the request. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/admin/') || SESSION_ENDPOINTS.includes(request.url)) {
    return next(request);
  }
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(withBearer(request, auth.accessToken())).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        catchError(() => {
          auth.clear();
          void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
          return throwError(() => error);
        }),
        switchMap((token) => next(withBearer(request, token))),
      );
    }),
  );
};

function withBearer(request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
}
