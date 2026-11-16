import { Component, computed, inject } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { AlertService } from '../../../core/services/alert.service';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.scss'
})
export class Shell {

  private readonly authService = inject(AuthService);
  private readonly notificationService = inject(NotificationService);
  private readonly alertService = inject(AlertService);

  protected readonly user = this.authService.user;
  protected readonly unreadCount = this.notificationService.unreadCount;
  protected readonly criticalCount = computed(() => this.alertService.getCriticalAlerts().length);
}
