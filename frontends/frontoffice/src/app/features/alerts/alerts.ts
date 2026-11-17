
import { Component, inject } from '@angular/core';
import { AlertService } from '../../core/services/alert.service';

@Component({
  selector: 'app-alerts',
  templateUrl: './alerts.html',
  styleUrl: './alerts.scss'
})
export class Alerts {
  private readonly alertService = inject(AlertService);

  protected readonly alerts = this.alertService.alerts;

  countBySeverity(severity: string): number {
    return this.alerts().filter(
      alert => alert.severity === severity
    ).length;
  }

  countByStatus(status: string): number {
    return this.alerts().filter(
      alert => alert.status === status
    ).length;
  }

  getAlertIcon(type: string): string {
    switch (type) {
      case 'FIRE':
        return '🔥';
      case 'GAS_LEAK':
        return '💨';
      case 'SMOKE':
        return '🌫️';
      case 'EARTHQUAKE':
        return '🌍';
      case 'FLOOD':
        return '🌊';
      default:
        return '⚠️';
    }
  }

  getSeverityClass(severity: string): string {
    switch (severity) {
      case 'CRITICAL':
        return 'severity-critical';
      case 'HIGH':
        return 'severity-high';
      case 'MEDIUM':
        return 'severity-medium';
      default:
        return 'severity-low';
    }
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'status-active';
      case 'ACKNOWLEDGED':
        return 'status-acknowledged';
      case 'RESOLVED':
        return 'status-resolved';
      default:
        return 'status-default';
    }
  }

  getStatusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'Active';
      case 'ACKNOWLEDGED':
        return 'Acknowledged';
      case 'RESOLVED':
        return 'Resolved';
      default:
        return status.replace(/_/g, ' ');
    }
  }

  getTypeLabel(type: string): string {
    return type.replace(/_/g, ' ');
  }

  getTimeAgo(dateStr: string): string {
    const diff = Date.now() - new Date(dateStr).getTime();

    if (!Number.isFinite(diff)) {
      return 'Date unavailable';
    }

    const minutes = Math.floor(diff / 60_000);

    if (minutes < 1) return 'Just now';
    if (minutes < 60) return `${minutes}m ago`;

    const hours = Math.floor(minutes / 60);

    if (hours < 24) return `${hours}h ago`;

    return `${Math.floor(hours / 24)}d ago`;
  }
}
