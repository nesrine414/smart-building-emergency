import { Component, computed, inject, OnInit, OnDestroy } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';

import { AuthService } from '../../../core/auth/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { AlertService } from '../../../core/services/alert.service';
import { RealtimeService } from '../../../core/services/realtime.service';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.scss'
})
export class Shell implements OnInit, OnDestroy {
  private readonly authService = inject(AuthService);
  private readonly notificationService = inject(NotificationService);
  private readonly alertService = inject(AlertService);
  private readonly realtimeService = inject(RealtimeService);

  protected readonly user = this.authService.user;
  protected readonly unreadCount = this.notificationService.unreadCount;
  protected readonly criticalCount = computed(
    () => this.alertService.getCriticalAlerts().length
  );
  protected readonly realtimeConnected = this.realtimeService.connected$;

  ngOnInit(): void {
    this.realtimeService.connect();
  }

  ngOnDestroy(): void {
    this.realtimeService.disconnect();
  }
}