import { Injectable, computed, inject, signal } from '@angular/core';
import { AppNotification } from '../models';
import { IncidentService } from './incident.service';

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly incidentService = inject(IncidentService);

  private readonly storageKey = 'smart-building-notifications-read-v1';

  private readonly readIds = signal<Set<string>>(this.loadReadIds());

  readonly notifications = computed<AppNotification[]>(() => {
    const read = this.readIds();

    return [...this.incidentService.incidents()]
      .sort((a, b) => Number(b.timestamp) - Number(a.timestamp))
      .map(incident => {
        const severity = String(incident.severity).toUpperCase();

        let displaySeverity: 'INFO' | 'WARNING' | 'DANGER' = 'INFO';

        if (severity === 'CRITICAL' || severity === 'HIGH') {
          displaySeverity = 'DANGER';
        } else if (severity === 'MEDIUM') {
          displaySeverity = 'WARNING';
        }

        const location = [
          incident.buildingId,
          incident.roomId
        ].filter(Boolean).join(' — ');

        const message = [
          incident.description,
          location ? `Emplacement : ${location}` : '',
          incident.recommendation
            ? `Consigne : ${incident.recommendation}`
            : ''
        ].filter(Boolean).join(' | ');

        const timestamp = Number(incident.timestamp);
        const createdAt = new Date(
          Number.isFinite(timestamp) && timestamp > 0
            ? timestamp
            : Date.now()
        ).toISOString();

        return {
          id: incident.id,
          type: 'INCIDENT',
          title: incident.title || incident.type.replace(/_/g, ' '),
          message,
          read: read.has(incident.id),
          createdAt,
          severity: displaySeverity,
          link: '/incidents'
        };
      });
  });

  readonly unreadCount = computed(
    () => this.notifications().filter(notification => !notification.read).length
  );

  markAsRead(id: string): void {
    this.readIds.update(current => {
      const updated = new Set(current);
      updated.add(id);
      this.saveReadIds(updated);
      return updated;
    });
  }

  markAllAsRead(): void {
    this.readIds.update(current => {
      const updated = new Set(current);

      for (const incident of this.incidentService.incidents()) {
        updated.add(incident.id);
      }

      this.saveReadIds(updated);
      return updated;
    });
  }

  private loadReadIds(): Set<string> {
    try {
      if (typeof localStorage === 'undefined') {
        return new Set<string>();
      }

      const raw = localStorage.getItem(this.storageKey);
      if (!raw) return new Set<string>();

      const parsed: unknown = JSON.parse(raw);

      if (!Array.isArray(parsed)) {
        return new Set<string>();
      }

      return new Set(
        parsed.filter((value): value is string =>
          typeof value === 'string'
        )
      );
    } catch {
      return new Set<string>();
    }
  }

  private saveReadIds(ids: Set<string>): void {
    try {
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem(
          this.storageKey,
          JSON.stringify([...ids])
        );
      }
    } catch (error) {
      console.warn('Impossible de mémoriser les notifications lues.', error);
    }
  }
}
