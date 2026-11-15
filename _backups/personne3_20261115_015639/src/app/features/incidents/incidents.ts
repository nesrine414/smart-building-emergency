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
      case 'IN_PROGRESS': return 'status-in-progress';
      case 'REPORTED': return 'status-reported';
      case 'CONTAINED': return 'status-contained';
      case 'RESOLVED': return 'status-resolved';
      default: return 'status-closed';
    }
  }

  getPriorityClass(priority: string): string {
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

  getTimeAgo(dateStr: string): string {
    const diff = Date.now() - new Date(dateStr).getTime();
    const minutes = Math.floor(diff / 60_000);
    if (minutes < 1) return 'Just now';
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
  }
}
