import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { UsersApi } from '../../api/users.api';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { Role, ROLE_LABELS } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { Modal } from '../../shared/ui/modal';
import { StatusBadge } from '../../shared/ui/status-badge';

const ASSIGNABLE_ROLES: Role[] = ['tenant_admin', 'operator', 'auditor', 'viewer'];
const MIN_PASSWORD = 12;

@Component({
  selector: 'app-users-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Modal, StatusBadge],
  templateUrl: './users-page.html',
})
export default class UsersPage {
  private readonly api = inject(UsersApi);
  private readonly notifier = inject(Notifier);
  protected readonly scope = inject(TenantScope);

  protected readonly users = rxResource({
    params: () => ({ tenantId: this.scope.tenantId() }),
    stream: () => this.api.list(),
  });
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly roles = ASSIGNABLE_ROLES;
  protected readonly minPassword = MIN_PASSWORD;
  /** A platform_admin creates users inside the tenant chosen in the top bar. */
  protected readonly canCreate = computed(() => !this.scope.needsTenant());

  protected readonly creating = signal(false);
  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly role = signal<Role>('operator');
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected openCreate(): void {
    this.email.set('');
    this.password.set('');
    this.role.set('operator');
    this.formError.set(null);
    this.creating.set(true);
  }

  protected create(): void {
    this.busy.set(true);
    this.api.create({ email: this.email().trim(), password: this.password(), role: this.role() }).subscribe({
      next: (user) => {
        this.busy.set(false);
        this.creating.set(false);
        this.password.set('');
        this.notifier.success(`Usuario ${user.email} creado. Deberá cambiar la contraseña al ingresar.`);
        this.users.reload();
      },
      error: (error) => {
        this.busy.set(false);
        this.formError.set(problemMessage(error));
      },
    });
  }

  protected errorOf(error: unknown): string {
    return problemMessage(error, 'No se pudo cargar la lista.');
  }
}
