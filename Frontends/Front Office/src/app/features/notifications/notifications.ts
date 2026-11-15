import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AppNotification } from '../../core/models';
import { NotificationService } from '../../core/services/notification.service';

@Component({
  selector: 'app-notifications',
  templateUrl: './notifications.html',
  styleUrl: './notifications.scss'
})
export class Notifications {
  private readonly notificationService = inject(NotificationService);
  private readonly router = inject(Router);

  protected readonly notifications = this.notificationService.notifications;
  protected readonly unreadCount = this.notificationService.unreadCount;

  markAsRead(id: string): void {
    this.notificationService.markAsRead(id);
  }

  markAllAsRead(): void {
    this.notificationService.markAllAsRead();
  }

  openNotification(notification: AppNotification): void {
    this.markAsRead(notification.id);
    void this.router.navigateByUrl(notification.link || '/incidents');
  }

  getTimeAgo(dateStr: string): string {
    const date = new Date(dateStr).getTime();

    if (!Number.isFinite(date)) return 'Date inconnue';

    const diff = Math.max(0, Date.now() - date);
    const minutes = Math.floor(diff / 60000);

    if (minutes < 1) return "À l'instant";
    if (minutes < 60) return `Il y a ${minutes} min`;

    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `Il y a ${hours} h`;

    return `Il y a ${Math.floor(hours / 24)} j`;
  }

  getSeverityClass(severity?: string): string {
    switch (severity) {
      case 'DANGER': return 'notif-danger';
      case 'WARNING': return 'notif-warning';
      default: return 'notif-info';
    }
  }
}
