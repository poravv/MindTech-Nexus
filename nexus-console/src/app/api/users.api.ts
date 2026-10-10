import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Role } from '../core/roles';
import { TenantScope } from '../core/tenant-scope.service';

export interface User {
  id: string;
  tenantId: string | null;
  email: string;
  role: Role;
  status: 'ACTIVE' | 'DISABLED';
  mustChangePassword: boolean;
}

export interface NewUser {
  email: string;
  password: string;
  role: Role;
}

/** /api/admin/users */
@Injectable({ providedIn: 'root' })
export class UsersApi {
  private readonly http = inject(HttpClient);
  private readonly scope = inject(TenantScope);
  private readonly url = '/api/admin/users';

  list(): Observable<User[]> {
    return this.http.get<User[]>(this.url, { params: this.scope.params() });
  }

  create(user: NewUser): Observable<User> {
    return this.http.post<User>(this.url, user, { params: this.scope.params() });
  }
}
