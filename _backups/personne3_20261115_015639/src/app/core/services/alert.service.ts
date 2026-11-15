import { Injectable, signal } from '@angular/core';
import { Alert, AlertSeverity, AlertStatus, AlertType } from '../models';

@Injectable({ providedIn: 'root' })
export class AlertService {

  private readonly alertList = signal<Alert[]>(this.getMockAlerts());

  readonly alerts = this.alertList.asReadonly();

  getActiveAlerts(): Alert[] {
    return this.alertList().filter(a => a.status === 'ACTIVE');
  }

  getCriticalAlerts(): Alert[] {
    return this.alertList().filter(a => a.severity === 'CRITICAL' && a.status === 'ACTIVE');
  }

  getAlertById(id: string): Alert | undefined {
    return this.alertList().find(a => a.id === id);
  }

  /**
   * Mock alerts — will be replaced by REST API calls.
   */
  private getMockAlerts(): Alert[] {
    return [
      {
        id: 'a-001',
        type: 'FIRE',
        severity: 'CRITICAL',
        status: 'ACTIVE',
        title: 'Fire Detected — Building A, Floor 3',
        description: 'Smoke and heat sensors triggered in Room 302. Immediate evacuation recommended.',
        buildingId: 'b-001',
        buildingName: 'Building A — Main Office',
        floorId: 'f-003',
        floorName: 'Floor 3',
        roomId: 'r-302',
        roomName: 'Room 302 — Server Room',
        triggeredAt: new Date(Date.now() - 5 * 60_000).toISOString(),
        recommendations: [
          'Evacuate Floor 3 immediately',
          'Use stairway B (nearest safe exit)',
          'Do not use elevators',
          'Proceed to Assembly Point C'
        ]
      },
      {
        id: 'a-002',
        type: 'GAS_LEAK',
        severity: 'HIGH',
        status: 'ACTIVE',
        title: 'Gas Leak Warning — Building A, Floor 1',
        description: 'Elevated methane levels detected in the utility room. Ventilation activated.',
        buildingId: 'b-001',
        buildingName: 'Building A — Main Office',
        floorId: 'f-001',
        floorName: 'Floor 1',
        roomId: 'r-101',
        roomName: 'Room 101 — Utility Room',
        triggeredAt: new Date(Date.now() - 15 * 60_000).toISOString(),
        recommendations: [
          'Avoid Floor 1 utility area',
          'Ventilation system activated automatically',
          'Maintenance team dispatched'
        ]
      },
      {
        id: 'a-003',
        type: 'SMOKE',
        severity: 'MEDIUM',
        status: 'ACKNOWLEDGED',
        title: 'Smoke Detected — Building B, Floor 2',
        description: 'Light smoke detected in kitchen area. Under investigation.',
        buildingId: 'b-002',
        buildingName: 'Building B — Annex',
        floorId: 'f-002',
        floorName: 'Floor 2',
        roomId: 'r-201',
        roomName: 'Room 201 — Kitchen',
        triggeredAt: new Date(Date.now() - 30 * 60_000).toISOString(),
        acknowledgedAt: new Date(Date.now() - 25 * 60_000).toISOString(),
        recommendations: [
          'Stay away from Floor 2 kitchen',
          'Investigation in progress'
        ]
      }
    ];
  }
}
