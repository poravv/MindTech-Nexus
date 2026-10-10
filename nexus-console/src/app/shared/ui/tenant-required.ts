import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Shown to a platform_admin on tenant-scoped screens until a tenant is chosen in the top bar. */
@Component({
  selector: 'app-tenant-required',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="alert alert-info" role="status">Elegí un tenant en la barra superior para ver esta sección.</p>`,
})
export class TenantRequired {}
