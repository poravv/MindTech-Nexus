import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { problemMessage } from '../../core/problem';
import { AuthFrame } from '../login/auth-frame';

const MIN_LENGTH = 12;

/** Forced after the first login with an initial password (spec §5.2): the API answers 403 until it is done. */
@Component({
  selector: 'app-change-password-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, AuthFrame],
  templateUrl: './change-password-page.html',
  styleUrl: '../login/login-page.scss',
})
export default class ChangePasswordPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly minLength = MIN_LENGTH;
  protected readonly email = computed(() => this.auth.user()?.email);
  protected readonly current = signal('');
  protected readonly next = signal('');
  protected readonly confirmation = signal('');
  protected readonly showPasswords = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly mismatch = computed(() => this.confirmation() !== '' && this.confirmation() !== this.next());

  protected async submit(): Promise<void> {
    if (this.next().length < MIN_LENGTH || this.mismatch()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.auth.changePassword(this.current(), this.next());
      await this.router.navigateByUrl('/');
    } catch (error) {
      this.error.set(problemMessage(error, 'No se pudo cambiar la contraseña.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/login');
  }
}
