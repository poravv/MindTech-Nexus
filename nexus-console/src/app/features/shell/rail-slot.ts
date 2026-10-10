import { Injectable, signal, TemplateRef } from '@angular/core';

/**
 * Lets the active page put its facet filters in the navigation rail, under the menu (as Argo CD does).
 * The page registers its template on init and clears it on destroy; the template keeps the page's context.
 */
@Injectable({ providedIn: 'root' })
export class RailSlot {
  readonly content = signal<TemplateRef<unknown> | null>(null);
}
