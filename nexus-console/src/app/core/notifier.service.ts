import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  kind: 'success' | 'error';
  message: string;
}

const VISIBLE_MS = 6000;

/** Short-lived feedback after an action. Errors that block a form are shown inline instead. */
@Injectable({ providedIn: 'root' })
export class Notifier {
  private nextId = 0;
  readonly toasts = signal<Toast[]>([]);

  success(message: string): void {
    this.push('success', message);
  }

  error(message: string): void {
    this.push('error', message);
  }

  dismiss(id: number): void {
    this.toasts.update((toasts) => toasts.filter((toast) => toast.id !== id));
  }

  private push(kind: Toast['kind'], message: string): void {
    const id = ++this.nextId;
    this.toasts.update((toasts) => [...toasts, { id, kind, message }]);
    setTimeout(() => this.dismiss(id), VISIBLE_MS);
  }
}
