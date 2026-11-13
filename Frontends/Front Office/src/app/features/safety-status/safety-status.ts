import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../core/services/alert.service';
import { BuildingService } from '../../core/services/building.service';
import { AuthService } from '../../core/auth/auth.service';
import { Floor } from '../../core/models';

@Component({
  selector: 'app-safety-status',
  imports: [RouterLink],
  templateUrl: './safety-status.html',
  styleUrl: './safety-status.scss'
})
export class SafetyStatus {

  private readonly authService = inject(AuthService);
  private readonly alertService = inject(AlertService);
  private readonly buildingService = inject(BuildingService);

  protected readonly user = this.authService.user;
  protected readonly buildings = this.buildingService.buildings;
  protected readonly activeAlerts = computed(() => this.alertService.getActiveAlerts());
  protected readonly criticalAlerts = computed(() => this.alertService.getCriticalAlerts());

  protected readonly overallStatus = computed(() => {
    if (this.criticalAlerts().length > 0) return 'DANGER';
    if (this.activeAlerts().length > 0) return 'WARNING';
    return 'SAFE';
  });

  protected readonly statusDetails = computed(() => {
    const status = this.overallStatus();
    switch (status) {
      case 'DANGER': return {
        icon: '🔴', label: 'DANGER', color: '#ef4444',
        message: 'Critical emergency detected. Follow evacuation procedures immediately.',
        instructions: [
          'Stay calm and begin evacuation',
          'Use the nearest safe stairway — do NOT use elevators',
          'Follow illuminated exit signs',
          'Help others if safe to do so',
          'Report to the assembly point',
          'Wait for all-clear announcement'
        ]
      };
      case 'WARNING': return {
        icon: '🟡', label: 'WARNING', color: '#eab308',
        message: 'Active alerts detected. Stay informed and be prepared to evacuate.',
        instructions: [
          'Monitor notifications for updates',
          'Locate nearest exits and stairways',
          'Prepare personal belongings',
          'Avoid affected areas',
          'Follow staff instructions'
        ]
      };
      default: return {
        icon: '🟢', label: 'ALL CLEAR', color: '#22c55e',
        message: 'All systems normal. No active alerts detected.',
        instructions: [
          'Review your evacuation route periodically',
          'Know the location of fire extinguishers',
          'Keep emergency contacts accessible',
          'Report any suspicious conditions'
        ]
      };
    }
  });

  protected readonly systemChecks = [
    { name: 'Fire Detection System', status: 'ONLINE', icon: '🔥' },
    { name: 'Smoke Sensors', status: 'ONLINE', icon: '🌫️' },
    { name: 'Gas Detection', status: 'ALERT', icon: '💨' },
    { name: 'Sprinkler System', status: 'ONLINE', icon: '💧' },
    { name: 'Emergency Lighting', status: 'ONLINE', icon: '💡' },
    { name: 'PA System', status: 'ONLINE', icon: '📢' },
    { name: 'Elevator Lock', status: 'ENGAGED', icon: '🛗' },
    { name: 'HVAC Ventilation', status: 'ACTIVE', icon: '🌬️' }
  ];

  getTotalOccupants(floor: Floor): number {
    return floor.rooms.reduce((sum, r) => sum + (r.currentOccupants ?? 0), 0);
  }

  getCheckStatusClass(status: string): string {
    switch (status) {
      case 'ONLINE': return 'check-online';
      case 'ACTIVE': return 'check-online';
      case 'ENGAGED': return 'check-warning';
      case 'ALERT': return 'check-alert';
      case 'OFFLINE': return 'check-offline';
      default: return 'check-online';
    }
  }
}
