import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

const REFRESH = '/api/admin/auth/refresh';
const TENANTS = '/api/admin/tenants';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => backend.verify());

  function signIn(token: string): void {
    auth.refresh().subscribe();
    backend.expectOne(REFRESH).flush(refreshed(token));
  }

  it('should send the access token as Bearer on admin API calls', () => {
    signIn('token-1');

    http.get(TENANTS).subscribe();

    expect(backend.expectOne(TENANTS).request.headers.get('Authorization')).toBe('Bearer token-1');
  });

  it('should not send the token to session endpoints or outside the admin API', () => {
    signIn('token-1');

    http.post('/api/admin/auth/login', {}).subscribe();
    http.get('/assets/config.json').subscribe();

    expect(backend.expectOne('/api/admin/auth/login').request.headers.has('Authorization')).toBe(false);
    expect(backend.expectOne('/assets/config.json').request.headers.has('Authorization')).toBe(false);
  });

  it('should refresh and retry once with the new token when the API answers 401', () => {
    signIn('expired');
    let body: unknown;

    http.get(TENANTS).subscribe((response) => (body = response));
    backend.expectOne(TENANTS).flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne(REFRESH).flush(refreshed('fresh'));
    const retry = backend.expectOne(TENANTS);
    retry.flush([{ id: 't1' }]);

    expect(retry.request.headers.get('Authorization')).toBe('Bearer fresh');
    expect(body).toEqual([{ id: 't1' }]);
  });

  it('should share a single refresh between requests that fail at the same time', () => {
    signIn('expired');

    http.get(TENANTS).subscribe();
    http.get('/api/admin/users').subscribe();
    backend.expectOne(TENANTS).flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/admin/users').flush(null, { status: 401, statusText: 'Unauthorized' });

    backend.expectOne(REFRESH).flush(refreshed('fresh'));
    backend.expectOne(TENANTS).flush([]);
    backend.expectOne('/api/admin/users').flush([]);
  });

  it('should end the session and go to login when the refresh is rejected', () => {
    signIn('expired');
    auth.user.set({ id: 'u1', email: 'a@b.c', role: 'operator', tenantId: 't1', mustChangePassword: false });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    let error: HttpErrorResponse | undefined;

    http.get(TENANTS).subscribe({ error: (e) => (error = e) });
    backend.expectOne(TENANTS).flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne(REFRESH).flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(error?.status).toBe(401);
    expect(auth.user()).toBeNull();
    expect(auth.accessToken()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.anything());
  });

  it('should not try to refresh on errors other than 401', () => {
    signIn('token-1');
    let error: HttpErrorResponse | undefined;

    http.get(TENANTS).subscribe({ error: (e) => (error = e) });
    backend.expectOne(TENANTS).flush(null, { status: 403, statusText: 'Forbidden' });

    backend.expectNone(REFRESH);
    expect(error?.status).toBe(403);
  });
});

function refreshed(accessToken: string) {
  return { accessToken, tokenType: 'Bearer', expiresIn: 900, mustChangePassword: false };
}
