import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, signal, viewChild } from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { TenantsApi } from '../../api/tenants.api';
import { AuthService } from '../../core/auth/auth.service';
import { ROLE_LABELS, Section, SECTION_ROLES } from '../../core/roles';
import { TenantScope } from '../../core/tenant-scope.service';
import { ThemeService } from '../../core/theme.service';
import { Icon, IconName } from '../../shared/ui/icon';
import { Logo } from '../../shared/ui/logo';
import { RailSlot } from './rail-slot';

interface NavItem {
  path: string;
  label: string;
  icon: IconName;
  section: Section;
}

const NAV: { label: string; items: NavItem[] }[] = [
  {
    label: 'Operación',
    items: [
      { path: '/', label: 'Resumen', icon: 'layout-grid', section: 'overview' },
      { path: '/integraciones', label: 'Integraciones', icon: 'workflow', section: 'integrations' },
      { path: '/operaciones', label: 'Operaciones', icon: 'activity', section: 'operations' },
    ],
  },
  {
    label: 'Configuración',
    items: [
      { path: '/conexiones', label: 'Conexiones', icon: 'plug', section: 'connections' },
      { path: '/clientes', label: 'Clientes m2m', icon: 'key-round', section: 'apiClients' },
    ],
  },
  {
    label: 'Administración',
    items: [
      { path: '/usuarios', label: 'Usuarios', icon: 'users', section: 'users' },
      { path: '/tenants', label: 'Tenants', icon: 'building', section: 'tenants' },
      { path: '/auditoria', label: 'Auditoría', icon: 'scroll-text', section: 'audit' },
    ],
  },
];

const THEME_LABELS = { system: 'Tema: sistema', light: 'Tema: claro', dark: 'Tema: oscuro' };
const COLLAPSED_KEY = 'nexus.rail-collapsed';

@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, RouterOutlet, RouterLink, RouterLinkActive, Icon, Logo],
  host: { '(document:click)': 'closeUserMenu($event)', '(document:keydown.escape)': 'userMenuOpen.set(false)' },
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export default class Shell {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly scope = inject(TenantScope);
  protected readonly theme = inject(ThemeService);
  protected readonly railSlot = inject(RailSlot);

  protected readonly user = this.auth.user;
  protected readonly roleLabel = computed(() => {
    const role = this.auth.role();
    return role ? ROLE_LABELS[role] : '';
  });
  protected readonly initials = computed(() => (this.user()?.email ?? '?').slice(0, 2).toUpperCase());
  protected readonly nav = computed(() => {
    const role = this.auth.role();
    return NAV.map((group) => ({
      label: group.label,
      items: group.items.filter((item) => role !== null && SECTION_ROLES[item.section].includes(role)),
    })).filter((group) => group.items.length > 0);
  });
  protected readonly themeLabel = computed(() => THEME_LABELS[this.theme.mode()]);
  private readonly tenantsApi = inject(TenantsApi);
  protected readonly tenants = rxResource({
    params: () => (this.scope.isPlatformAdmin() ? { version: this.tenantsApi.changes() } : undefined),
    stream: () => this.tenantsApi.list(),
    defaultValue: [],
  });
  protected readonly menuOpen = signal(false);
  protected readonly userMenuOpen = signal(false);
  protected readonly collapsed = signal(readCollapsed());

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );
  /** Section of the current URL plus "Detalle" for a /section/:id page. */
  protected readonly crumbs = computed(() => {
    const [first, second] = this.url().split(/[?#]/)[0].split('/').filter(Boolean);
    const item = NAV.flatMap((group) => group.items).find((nav) => nav.path === `/${first ?? ''}`);
    const crumbs: { label: string; path?: string }[] = [{ label: item?.label ?? 'Nexus', path: item?.path }];
    if (second) {
      crumbs.push({ label: 'Detalle' });
    }
    return crumbs;
  });

  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');

  constructor() {
    // After each navigation, close the mobile menu and move focus to the new content for screen readers.
    this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd), takeUntilDestroyed())
      .subscribe(() => {
        this.menuOpen.set(false);
        this.userMenuOpen.set(false);
        this.main().nativeElement.focus({ preventScroll: true });
      });
  }

  protected toggleCollapsed(): void {
    this.collapsed.update((value) => !value);
    try {
      localStorage.setItem(COLLAPSED_KEY, String(this.collapsed()));
    } catch {
      // Without storage the choice lasts until reload.
    }
  }

  protected closeUserMenu(event: Event): void {
    if (!(event.target as Element).closest('.user-menu')) {
      this.userMenuOpen.set(false);
    }
  }

  protected selectTenant(value: string): void {
    this.scope.select(value || null);
  }

  protected async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/login');
  }
}

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === 'true';
  } catch {
    return false;
  }
}
