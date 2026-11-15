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
  protected email = signal('');
  protected password = signal('');
  protected errorMessage = signal('');
  protected isLoading = signal(false);

  constructor(
    private readonly authService: AuthService,
    private readonly router: Router
  ) {
    if (this.authService.isAuthenticated()) {
      this.router.navigate(['/dashboard']);
    }
  }

  onSubmit(): void {
    this.isLoading.set(true);
    this.errorMessage.set('');

    const success = this.authService.login({
      email: this.email(),
      password: this.password()
    });

    if (success) {
      this.router.navigate(['/dashboard']);
    } else {
      this.errorMessage.set('Invalid email or password. Try user@smartbuilding.com / password');
    }

    this.isLoading.set(false);
  }
}
