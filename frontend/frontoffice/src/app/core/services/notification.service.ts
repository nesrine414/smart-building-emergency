import { Injectable, signal, computed } from '@angular/core';
import { AppNotification } from '../models';

@Injectable({ providedIn: 'root' })
export class NotificationService {

  private readonly notificationList = signal<AppNotification[]>(this.getMockNotifications());

  readonly notifications = this.notificationList.asReadonly();
  readonly unreadCount = computed(() => this.notificationList().filter(n => !n.read).length);

  markAsRead(id: string): void {
    this.notificationList.update(list =>
      list.map(n => n.id === id ? { ...n, read: true } : n)
    );
  }

  markAllAsRead(): void {
    this.notificationList.update(list =>
      list.map(n => ({ ...n, read: true }))
    );
  }

  private getMockNotifications(): AppNotification[] {
    return [
      {
        id: 'n-001',
        type: 'ALERT',
        title: 'CRITICAL: Fire Detected',
        message: 'Fire detected in Building A, Floor 3, Room 302. Evacuate immediately.',
        read: false,
        createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
        severity: 'DANGER',
        link: '/alerts'
      },
      {
        id: 'n-002',
        type: 'ALERT',
        title: 'WARNING: Gas Leak',
        message: 'Gas leak detected in Building A, Floor 1. Avoid the area.',
        read: false,
        createdAt: new Date(Date.now() - 15 * 60_000).toISOString(),
        severity: 'WARNING',
        link: '/alerts'
      },
      {
        id: 'n-003',
        type: 'RECOMMENDATION',
        title: 'AI Safety Recommendation',
        message: 'Based on current conditions, use Exit B on Floor 2 for fastest evacuation.',
        read: false,
        createdAt: new Date(Date.now() - 8 * 60_000).toISOString(),
        severity: 'INFO',
        link: '/building-map'
      },
      {
        id: 'n-004',
        type: 'SYSTEM',
        title: 'System Status Update',
        message: 'All fire suppression systems on Floor 3 are active and operational.',
        read: true,
        createdAt: new Date(Date.now() - 3 * 60_000).toISOString(),
        severity: 'INFO'
      }
    ];
  }
}
