import {
  Component,
  inject,
  signal
} from '@angular/core';

import {
  ActivatedRoute,
  Router
} from '@angular/router';

import { AuthService } from '../core/auth/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  templateUrl: './login.html',
  styleUrl: './login.scss'
})
export class LoginComponent {

  protected readonly errorMessage = signal('');
  protected readonly isLoading = signal(false);

  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  constructor() {
    this.auth.clearAccessDenied();

    const reason = this.route.snapshot.queryParamMap.get('reason');

    switch (reason) {
      case 'forbidden':
        this.errorMessage.set(
          'Access is restricted to administrators.'
        );
        break;

      case 'expired':
        this.errorMessage.set(
          'Your session has expired. Please sign in again.'
        );
        break;

      case 'oauth_error':
        this.errorMessage.set(
          'Authentication failed. Please try again.'
        );
        break;
    }

    // Do not restart OAuth if an admin session already exists.
    if (this.auth.isAdmin()) {
      void this.router.navigateByUrl('/dashboard', {
        replaceUrl: true
      });
    }
  }

  async startLogin(): Promise<void> {

    // Prevent multiple clicks.
    if (this.isLoading()) {
      return;
    }

    // Do not start a new authentication flow if already authenticated.
    if (this.auth.isAdmin()) {
      await this.router.navigateByUrl('/dashboard', {
        replaceUrl: true
      });
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    try {
      // Keep the IAM navigation in the current browser tab.
      await this.auth.beginLogin();

    } catch (error: unknown) {

      console.error(
        '[LOGIN] OAuth2 sign-in initialization failed:',
        error
      );

      this.isLoading.set(false);

      this.errorMessage.set(
        'Unable to start authentication. Please check that the IAM server is available.'
      );
    }
  }
}