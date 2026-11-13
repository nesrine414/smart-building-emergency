import { Component, inject } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-profile',
  templateUrl: './profile.html',
  styleUrl: './profile.scss'
})
export class Profile {

  private readonly authService = inject(AuthService);
  protected readonly user = this.authService.user;

  logout(): void {
    this.authService.logout();
  }
}
