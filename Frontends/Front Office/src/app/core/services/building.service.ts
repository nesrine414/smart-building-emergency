import { Injectable, signal } from '@angular/core';
import { Building } from '../models';

@Injectable({ providedIn: 'root' })
export class BuildingService {

  private readonly buildingList = signal<Building[]>(this.getMockBuildings());

  readonly buildings = this.buildingList.asReadonly();

  getBuildingById(id: string): Building | undefined {
    return this.buildingList().find(b => b.id === id);
  }

  private getMockBuildings(): Building[] {
    return [
      {
        id: 'b-001',
        name: 'Building A — Main Office',
        address: '123 Smart Street, Tech City',
        latitude: 36.8065,
        longitude: 10.1815,
        status: 'DANGER',
        totalOccupants: 245,
        floors: [
          {
            id: 'f-001',
            buildingId: 'b-001',
            name: 'Floor 1 — Ground',
            level: 1,
            rooms: [
              { id: 'r-101', floorId: 'f-001', name: 'Lobby', type: 'LOBBY', capacity: 50, currentOccupants: 12 },
              { id: 'r-102', floorId: 'f-001', name: 'Utility Room', type: 'UTILITY', capacity: 5, currentOccupants: 0 }
            ]
          },
          {
            id: 'f-002',
            buildingId: 'b-001',
            name: 'Floor 2 — Offices',
            level: 2,
            rooms: [
              { id: 'r-201', floorId: 'f-002', name: 'Open Space A', type: 'OFFICE', capacity: 60, currentOccupants: 45 },
              { id: 'r-202', floorId: 'f-002', name: 'Meeting Room B', type: 'OFFICE', capacity: 15, currentOccupants: 8 }
            ]
          },
          {
            id: 'f-003',
            buildingId: 'b-001',
            name: 'Floor 3 — IT & Servers',
            level: 3,
            rooms: [
              { id: 'r-301', floorId: 'f-003', name: 'IT Office', type: 'OFFICE', capacity: 30, currentOccupants: 0 },
              { id: 'r-302', floorId: 'f-003', name: 'Server Room', type: 'UTILITY', capacity: 10, currentOccupants: 0 }
            ]
          }
        ]
      },
      {
        id: 'b-002',
        name: 'Building B — Annex',
        address: '125 Smart Street, Tech City',
        latitude: 36.8070,
        longitude: 10.1820,
        status: 'WARNING',
        totalOccupants: 80,
        floors: [
          {
            id: 'f-004',
            buildingId: 'b-002',
            name: 'Floor 1',
            level: 1,
            rooms: [
              { id: 'r-401', floorId: 'f-004', name: 'Reception', type: 'LOBBY', capacity: 20, currentOccupants: 5 }
            ]
          },
          {
            id: 'f-005',
            buildingId: 'b-002',
            name: 'Floor 2',
            level: 2,
            rooms: [
              { id: 'r-201', floorId: 'f-005', name: 'Kitchen', type: 'OTHER', capacity: 20, currentOccupants: 3 }
            ]
          }
        ]
      }
    ];
  }
}
