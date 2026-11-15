import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../core/services/alert.service';
import { IncidentService } from '../../core/services/incident.service';
import { BuildingService } from '../../core/services/building.service';
import { NotificationService } from '../../core/services/notification.service';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss'
})
export class Dashboard {

  private readonly authService = inject(AuthService);
  private readonly alertService = inject(AlertService);
  private readonly incidentService = inject(IncidentService);
  private readonly buildingService = inject(BuildingService);
  private readonly notificationService = inject(NotificationService);

  protected readonly user = this.authService.user;
  protected readonly alerts = this.alertService.alerts;
  protected readonly incidents = this.incidentService.incidents;
  protected readonly buildings = this.buildingService.buildings;
  protected readonly unreadNotifications = this.notificationService.unreadCount;

  protected readonly activeAlerts = computed(() => this.alertService.getActiveAlerts());
  protected readonly criticalAlerts = computed(() => this.alertService.getCriticalAlerts());
  protected readonly activeIncidents = computed(() => this.incidentService.getActiveIncidents());

  protected readonly overallStatus = computed(() => {
    const critical = this.criticalAlerts().length;
    if (critical > 0) return 'DANGER';
    const active = this.activeAlerts().length;
    if (active > 0) return 'WARNING';
    return 'SAFE';
  });

  protected readonly statusConfig = computed(() => {
    const status = this.overallStatus();
    switch (status) {
      case 'DANGER':
        return { label: 'DANGER', icon: '🔴', class: 'status-danger', message: 'Critical alerts active. Follow evacuation instructions.' };
      case 'WARNING':
        return { label: 'WARNING', icon: '🟡', class: 'status-warning', message: 'Active alerts detected. Stay informed and be prepared.' };
      default:
        return { label: 'SAFE', icon: '🟢', class: 'status-safe', message: 'All systems normal. No active alerts.' };
    }
  });

  getTimeAgo(dateStr: string): string {
    const diff = Date.now() - new Date(dateStr).getTime();
    const minutes = Math.floor(diff / 60_000);
    if (minutes < 1) return 'Just now';
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
  }

  getSeverityClass(severity: string): string {
    switch (severity) {
      case 'CRITICAL': return 'severity-critical';
      case 'HIGH': return 'severity-high';
      case 'MEDIUM': return 'severity-medium';
      default: return 'severity-low';
    }
  }

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
}
