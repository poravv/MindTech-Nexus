import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { finalize, firstValueFrom, map, Observable, shareReplay, tap } from 'rxjs';
import { Role } from '../roles';

export const AUTH_URL = '/api/admin/auth';

export interface CurrentUser {
  id: string;
  email: string;
  role: Role;
  tenantId: string | null;
  mustChangePassword: boolean;
}

interface AccessTokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  mustChangePassword: boolean;
}

/**
 * Console session. The access token lives only in memory; the refresh token is an HttpOnly cookie
 * the browser sends to /api/admin/auth by itself (spec §5.2).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly token = signal<string | null>(null);
  private refreshInFlight: Observable<string> | null = null;

  readonly user = signal<CurrentUser | null>(null);
  readonly role = computed(() => this.user()?.role ?? null);

  accessToken(): string | null {
    return this.token();
  }

  async login(email: string, password: string): Promise<CurrentUser> {
    const response = await firstValueFrom(this.http.post<AccessTokenResponse>(`${AUTH_URL}/login`, { email, password }));
    this.token.set(response.accessToken);
    return this.loadCurrentUser();
  }

  /** Restores the session from the refresh cookie at startup; stays anonymous if there is none. */
  async restore(): Promise<void> {
    try {
      await firstValueFrom(this.refresh());
      await this.loadCurrentUser();
    } catch {
      this.clear();
    }
  }

  /** Renews the access token. Concurrent callers share a single request so the cookie rotates once. */
  refresh(): Observable<string> {
    this.refreshInFlight ??= this.http.post<AccessTokenResponse>(`${AUTH_URL}/refresh`, {}).pipe(
      map((response) => response.accessToken),
      tap((token) => this.token.set(token)),
      finalize(() => (this.refreshInFlight = null)),
      shareReplay(1),
    );
    return this.refreshInFlight;
  }

  /** Mandatory after the bootstrap login; a refresh then issues a token without the restriction. */
  async changePassword(currentPassword: string, newPassword: string): Promise<CurrentUser> {
    await firstValueFrom(this.http.post<void>(`${AUTH_URL}/password`, { currentPassword, newPassword }));
    await firstValueFrom(this.refresh());
    return this.loadCurrentUser();
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post<void>(`${AUTH_URL}/logout`, {}));
    } finally {
      this.clear();
    }
  }

  clear(): void {
    this.token.set(null);
    this.user.set(null);
  }

  private async loadCurrentUser(): Promise<CurrentUser> {
    const user = await firstValueFrom(this.http.get<CurrentUser>(`${AUTH_URL}/me`));
    this.user.set(user);
    return user;
  }
}
