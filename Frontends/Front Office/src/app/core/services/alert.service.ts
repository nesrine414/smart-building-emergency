import { Injectable, computed, inject } from '@angular/core';
import { Alert, AlertStatus } from '../models';
import { IncidentService } from './incident.service';

@Injectable({ providedIn: 'root' })
export class AlertService {
  private readonly incidentService = inject(IncidentService);

  readonly alerts = computed<Alert[]>(() =>
    this.incidentService.incidents().map(incident => {
      const status: AlertStatus =
        incident.status === 'OPEN' ? 'ACTIVE' :
        incident.status === 'ACKNOWLEDGED' ? 'ACKNOWLEDGED' : 'RESOLVED';

      return {
        id: incident.id,
        type: incident.type as Alert['type'],
        severity: incident.severity,
        status,
        title: incident.type.replace(/_/g, ' '),
        description: incident.description,
        buildingId: incident.buildingId,
        buildingName: incident.buildingId,
        roomId: incident.roomId,
        roomName: incident.roomId,
        sensorId: incident.sensorId,
        triggeredAt: new Date(incident.timestamp).toISOString(),
        recommendations: incident.recommendation
          ? [incident.recommendation]
          : []
      };
    })
  );

  getActiveAlerts(): Alert[] {
    return this.alerts().filter(alert => alert.status === 'ACTIVE');
  }

  getCriticalAlerts(): Alert[] {
    return this.alerts().filter(
      alert => alert.severity === 'CRITICAL' && alert.status === 'ACTIVE'
    );
  }

  getAlertById(id: string): Alert | undefined {
    return this.alerts().find(alert => alert.id === id);
  }
}