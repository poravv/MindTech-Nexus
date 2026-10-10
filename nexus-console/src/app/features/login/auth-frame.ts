import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Logo } from '../../shared/ui/logo';

/**
 * Split layout of the sign-in screens: brand panel with the integration diagram on wide screens,
 * logo on top of the form on small ones. The form goes in as content.
 */
@Component({
  selector: 'app-auth-frame',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Logo],
  templateUrl: './auth-frame.html',
  styleUrl: './auth-frame.scss',
})
export class AuthFrame {}
