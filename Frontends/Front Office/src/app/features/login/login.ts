
import { Component, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.scss'
})
export class Login {
  protected username = signal('');
  protected password = signal('');
  protected errorMessage = signal('');
  protected isLoading = signal(false);

  constructor(
    private readonly authService: AuthService,
    private readonly router: Router
  ) {
    const user = this.authService.user();

    if (this.authService.isAuthenticated() && user?.role === 'OCCUPANT') {
      this.router.navigate(['/dashboard']);
    }
  }

  onSubmit(): void {
    if (this.isLoading()) return;

    this.isLoading.set(true);
    this.errorMessage.set('');

    this.authService.login({
      username: this.username().trim(),
      password: this.password()
    }).subscribe({
      next: (user) => {
        if (user.role !== 'OCCUPANT') {
          this.authService.logout();
          this.errorMessage.set(
            'Accès refusé : cette interface est réservée aux occupants.'
          );
          this.isLoading.set(false);
          return;
        }

        this.router.navigate(['/dashboard']);
      },
      error: (error) => {
        this.isLoading.set(false);
        this.errorMessage.set(
          error.status === 401
            ? 'Identifiant ou mot de passe incorrect.'
            : 'Impossible de contacter le serveur IAM.'
        );
      },
      complete: () => {
        this.isLoading.set(false);
      }
    });
  }
}
