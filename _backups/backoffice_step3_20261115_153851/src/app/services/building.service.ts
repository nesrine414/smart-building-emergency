
import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { Building } from '../models/building.model';

@Injectable({
  providedIn: 'root'
})
export class BuildingService {

  private readonly buildings: Building[] = [
    {
      id: 'building-1',
      name: 'Smart Building',
      address: 'Main Building',
      floors: [
        {
          id: 'floor-0',
          name: 'Ground Floor',
          rooms: [
            { id: 'room-101', name: 'Office 101', type: 'OFFICE' },
            { id: 'room-102', name: 'Office 102', type: 'OFFICE' },
            { id: 'room-103', name: 'Office 103', type: 'OFFICE' },
            { id: 'hallway-1', name: 'Main Hallway', type: 'HALLWAY' },
            { id: 'exit-1', name: 'Emergency Exit', type: 'EXIT' },
            { id: 'stairs-1', name: 'Staircase', type: 'STAIRCASE' }
          ]
        }
      ]
    }
  ];

  getBuildings(): Observable<Building[]> {
    return of(this.buildings);
  }

  getBuildingById(id: string): Observable<Building | undefined> {
    return of(this.buildings.find(building => building.id === id));
  }
}
