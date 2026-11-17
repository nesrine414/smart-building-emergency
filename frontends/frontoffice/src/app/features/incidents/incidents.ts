import { Component, inject } from '@angular/core';
import { IncidentService } from '../../core/services/incident.service';

@Component({
  selector: 'app-incidents',
  templateUrl: './incidents.html',
  styleUrl: './incidents.scss'
})
export class Incidents {
  private readonly incidentService = inject(IncidentService);
  protected readonly incidents = this.incidentService.incidents;

  getStatusClass(status: string): string {
    switch (status) {
      case 'OPEN':
      case 'REPORTED': return 'status-reported';
      case 'ACKNOWLEDGED':
      case 'IN_PROGRESS': return 'status-in-progress';
      case 'RESOLVED': return 'status-resolved';
      case 'CONTAINED': return 'status-contained';
      default: return 'status-closed';
    }
  }

  getPriorityClass(priority?: string): string {
    switch (priority) {
      case 'CRITICAL': return 'priority-critical';
      case 'HIGH': return 'priority-high';
      case 'MEDIUM': return 'priority-medium';
      default: return 'priority-low';
    }
  }

  getTypeIcon(type: string): string {
    switch (type) {
      case 'FIRE': return '🔥';
      case 'GAS_LEAK': return '💨';
      case 'SMOKE': return '🌫️';
      case 'EARTHQUAKE': return '🌍';
      default: return '⚠️';
    }
  }

  getTimeAgo(dateValue?: string | number): string {
    if (dateValue === undefined) return 'Date inconnue';

    const diff = Date.now() - new Date(dateValue).getTime();
    if (!Number.isFinite(diff)) return 'Date inconnue';

    const minutes = Math.max(0, Math.floor(diff / 60000));
    if (minutes < 1) return 'À l’instant';
    if (minutes < 60) return `Il y a ${minutes} min`;

    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `Il y a ${hours} h`;

    return `Il y a ${Math.floor(hours / 24)} j`;
  }
}