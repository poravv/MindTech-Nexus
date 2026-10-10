import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TenantsApi } from '../../api/tenants.api';
import { Notifier } from '../../core/notifier.service';
import { problemMessage } from '../../core/problem';
import { Modal } from '../../shared/ui/modal';
import { StatusBadge } from '../../shared/ui/status-badge';

@Component({
  selector: 'app-tenants-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule, Modal, StatusBadge],
  templateUrl: './tenants-page.html',
})
export default class TenantsPage {
  private readonly api = inject(TenantsApi);
  private readonly notifier = inject(Notifier);

  protected readonly tenants = rxResource({ stream: () => this.api.list() });
  protected readonly creating = signal(false);
  protected readonly slug = signal('');
  protected readonly name = signal('');
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected openCreate(): void {
    this.slug.set('');
    this.name.set('');
    this.formError.set(null);
    this.creating.set(true);
  }

  protected create(): void {
    this.busy.set(true);
    this.api.create(this.slug().trim(), this.name().trim()).subscribe({
      next: (tenant) => {
        this.busy.set(false);
        this.creating.set(false);
        this.notifier.success(`Tenant ${tenant.name} creado.`);
        this.tenants.reload();
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
