import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.scss'
})
export class LoginComponent {
  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly errorMessage = signal('');
  protected readonly isLoading = signal(false);

  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  constructor() {
    this.auth.clearAccessDenied();

    const reason = this.route.snapshot.queryParamMap.get('reason');

    if (reason === 'forbidden') {
      this.errorMessage.set('Réservé aux administrateurs.');
    } else if (reason === 'expired') {
      this.errorMessage.set('Votre session a expiré. Veuillez vous reconnecter.');
    }

    if (this.auth.isAdmin()) {
      void this.router.navigateByUrl('/dashboard');
    }
  }

  onSubmit(): void {
    if (this.isLoading()) {
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    this.auth.login({
      username: this.username().trim(),
      password: this.password()
    }).subscribe({
      next: () => {
        void this.router.navigateByUrl('/dashboard');
      },
      error: (error: unknown) => {
        this.isLoading.set(false);

        if (error instanceof Error && error.message.startsWith('Accès réservé')) {
          this.errorMessage.set(error.message);
        } else if (
          typeof error === 'object' &&
          error !== null &&
          'status' in error &&
          error.status === 401
        ) {
          this.errorMessage.set('Identifiant ou mot de passe incorrect.');
        } else {
          this.errorMessage.set('Connexion impossible. Vérifiez le serveur IAM.');
        }
      },
      complete: () => this.isLoading.set(false)
    });
  }
}
