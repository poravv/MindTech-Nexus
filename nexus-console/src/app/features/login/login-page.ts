import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { problemMessage } from '../../core/problem';

@Component({
  selector: 'app-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './login-page.html',
  styleUrl: './login-page.scss',
})
export default class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly returnUrl = input<string>();

  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async submit(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const user = await this.auth.login(this.email().trim(), this.password());
      const target = user.mustChangePassword ? '/cambiar-clave' : safeReturnUrl(this.returnUrl());
      await this.router.navigateByUrl(target);
    } catch (error) {
      this.error.set(loginError(error));
    } finally {
      this.password.set('');
      this.busy.set(false);
    }
  }
}

function loginError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401) return 'Email o contraseña incorrectos.';
    if (error.status === 423) return 'Cuenta bloqueada temporalmente por intentos fallidos. Probá más tarde.';
    if (error.status === 429) return 'Demasiados intentos. Esperá un minuto y volvé a probar.';
  }
  return problemMessage(error, 'No se pudo iniciar sesión.');
}

/** Only internal paths: a crafted ?returnUrl must not send the user to another site. */
function safeReturnUrl(url: string | undefined): string {
  return url && url.startsWith('/') && !url.startsWith('//') ? url : '/';
}
