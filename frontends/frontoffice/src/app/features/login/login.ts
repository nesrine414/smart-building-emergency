
import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  templateUrl: './login.html',
  styleUrl: './login.scss'
})
export class Login {
  protected readonly errorMessage = signal('');
  protected readonly isLoading = signal(false);

  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  constructor() {
    if (
      this.authService.isAuthenticated() &&
      this.authService.user()?.role === 'OCCUPANT'
    ) {
      void this.router.navigateByUrl('/dashboard');
    }
  }

  async onSubmit(): Promise<void> {
    if (this.isLoading()) {
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    try {
      await this.authService.beginLogin();
    } catch (error) {
      console.error('OIDC login initialization failed:', error);

      this.errorMessage.set(
        'Impossible de démarrer la connexion sécurisée avec IAM. Vérifiez que le serveur IAM est disponible.'
      );

      this.isLoading.set(false);
    }
  }
}
