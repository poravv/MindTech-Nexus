import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, signal, viewChild } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { TenantsApi } from '../../api/tenants.api';
import { AuthService } from '../../core/auth/auth.service';
import { ROLE_LABELS, Section, SECTION_ROLES } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { ThemeService } from '../../core/theme.service';

const NAV: { path: string; label: string; section: Section }[] = [
  { path: '/', label: 'Resumen', section: 'overview' },
  { path: '/operaciones', label: 'Operaciones', section: 'operations' },
  { path: '/integraciones', label: 'Integraciones', section: 'integrations' },
  { path: '/conexiones', label: 'Conexiones', section: 'connections' },
  { path: '/clientes', label: 'Clientes m2m', section: 'apiClients' },
  { path: '/usuarios', label: 'Usuarios', section: 'users' },
  { path: '/tenants', label: 'Tenants', section: 'tenants' },
  { path: '/auditoria', label: 'Auditoría', section: 'audit' },
];

const THEME_LABELS = { system: 'Tema: sistema', light: 'Tema: claro', dark: 'Tema: oscuro' };

@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export default class Shell {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly scope = inject(TenantScope);
  protected readonly theme = inject(ThemeService);

  protected readonly user = this.auth.user;
  protected readonly roleLabel = computed(() => {
    const role = this.auth.role();
    return role ? ROLE_LABELS[role] : '';
  });
  protected readonly nav = computed(() => {
    const role = this.auth.role();
    return NAV.filter((item) => role !== null && SECTION_ROLES[item.section].includes(role));
  });
  protected readonly themeLabel = computed(() => THEME_LABELS[this.theme.mode()]);
  private readonly tenantsApi = inject(TenantsApi);
  protected readonly tenants = rxResource({
    params: () => (this.scope.isPlatformAdmin() ? { version: this.tenantsApi.changes() } : undefined),
    stream: () => this.tenantsApi.list(),
    defaultValue: [],
  });
  protected readonly menuOpen = signal(false);

  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');

  constructor() {
    // After each navigation, close the mobile menu and move focus to the new content for screen readers.
    this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd), takeUntilDestroyed())
      .subscribe(() => {
        this.menuOpen.set(false);
        this.main().nativeElement.focus({ preventScroll: true });
      });
  }

  protected selectTenant(value: string): void {
    this.scope.select(value || null);
  }

  protected async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/login');
  }
}
