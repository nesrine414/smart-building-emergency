import { Injectable, signal } from '@angular/core';
import { Incident } from '../models';

@Injectable({ providedIn: 'root' })
export class IncidentService {

  private readonly incidentList = signal<Incident[]>(this.getMockIncidents());

  readonly incidents = this.incidentList.asReadonly();

  getActiveIncidents(): Incident[] {
    return this.incidentList().filter(i => i.status !== 'CLOSED' && i.status !== 'RESOLVED');
  }

  getIncidentById(id: string): Incident | undefined {
    return this.incidentList().find(i => i.id === id);
  }

  private getMockIncidents(): Incident[] {
    return [
      {
        id: 'inc-001',
        title: 'Fire in Server Room',
        description: 'Fire detected by multiple sensors in Room 302. Fire suppression activated.',
        status: 'IN_PROGRESS',
        priority: 'CRITICAL',
        type: 'FIRE',
        buildingId: 'b-001',
        buildingName: 'Building A — Main Office',
        floorId: 'f-003',
        floorName: 'Floor 3',
        roomId: 'r-302',
        roomName: 'Room 302 — Server Room',
        reportedBy: 'System (Automatic)',
        reportedAt: new Date(Date.now() - 5 * 60_000).toISOString(),
        updatedAt: new Date(Date.now() - 2 * 60_000).toISOString(),
        alerts: ['a-001'],
        assignedTo: ['Fire Response Team'],
        notes: ['Automatic fire suppression activated', 'Floor 3 evacuation in progress']
      },
      {
        id: 'inc-002',
        title: 'Gas Leak in Utility Room',
        description: 'Methane levels above threshold in utility room. Ventilation engaged.',
        status: 'REPORTED',
        priority: 'HIGH',
        type: 'GAS_LEAK',
        buildingId: 'b-001',
        buildingName: 'Building A — Main Office',
        floorId: 'f-001',
        floorName: 'Floor 1',
        roomId: 'r-101',
        roomName: 'Room 101 — Utility Room',
        reportedBy: 'System (Automatic)',
        reportedAt: new Date(Date.now() - 15 * 60_000).toISOString(),
        updatedAt: new Date(Date.now() - 10 * 60_000).toISOString(),
        alerts: ['a-002'],
        assignedTo: ['Maintenance Team']
      }
    ];
  }
}
