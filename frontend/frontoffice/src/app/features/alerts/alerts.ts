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

  getAlertIcon(type: string): string {
    switch (type) {
      case 'FIRE': return '🔥';
      case 'GAS_LEAK': return '💨';
      case 'SMOKE': return '🌫️';
      case 'EARTHQUAKE': return '🌍';
      case 'FLOOD': return '🌊';
      default: return '⚠️';
    }
  }

  getSeverityClass(severity: string): string {
    switch (severity) {
      case 'CRITICAL': return 'severity-critical';
      case 'HIGH': return 'severity-high';
      case 'MEDIUM': return 'severity-medium';
      default: return 'severity-low';
    }
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'ACTIVE': return 'status-active';
      case 'ACKNOWLEDGED': return 'status-acknowledged';
      case 'RESOLVED': return 'status-resolved';
      default: return 'status-default';
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
