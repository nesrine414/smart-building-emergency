import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, forkJoin, switchMap, map, tap } from 'rxjs';
import { Building, BuildingView, Floor, Room, RoomView } from '../models';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class BuildingService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${environment.apiUrl}/buildings`;

  private readonly buildingList = signal<BuildingView[]>([]);
  readonly buildings = this.buildingList.asReadonly();

  constructor() {
    this.refresh().subscribe({
      error: error => console.error('Chargement bâtiments impossible', error)
    });
  }

  refresh(): Observable<BuildingView[]> {
    if (environment.useMock) {
      return of(this.getMockBuildings()).pipe(
        tap(items => this.buildingList.set(items))
      );
    }

    return this.http.get<Building[]>(this.apiUrl).pipe(
      switchMap(buildings => {
        if (buildings.length === 0) return of([]);

        return forkJoin(
          buildings.map(building =>
            this.http.get<Room[]>(
              `${this.apiUrl}/${encodeURIComponent(building.id)}/rooms`
            ).pipe(
              map(rooms => this.toBuildingView(building, rooms))
            )
          )
        );
      }),
      tap(items => this.buildingList.set(items))
    );
  }

  getBuildings(): Observable<BuildingView[]> {
    return this.refresh();
  }

  getBuildingById(id: string): BuildingView | undefined {
    return this.buildingList().find(item => item.id === id);
  }

  private toBuildingView(building: Building, rooms: Room[]): BuildingView {
    const levels = new Set<number>();

    for (let level = 1; level <= building.floors; level++) {
      levels.add(level);
    }

    rooms.forEach(room => levels.add(room.floor));

    const floors: Floor[] = Array.from(levels).sort((a, b) => a - b).map(level => {
      const floorRooms: RoomView[] = rooms
        .filter(room => room.floor === level)
        .map(room => ({
          ...room,
          name: `Office ${room.roomNumber}`,
          floorId: `floor-${level}`,
          type: 'OFFICE'
        }));

      return {
        id: `floor-${level}`,
        buildingId: building.id,
        name: `Floor ${level}`,
        level,
        rooms: floorRooms
      };
    });

    return {
      ...building,
      floors,
      status: 'SAFE'
    };
  }

  private getMockBuildings(): BuildingView[] {
    const buildings: Building[] = [
      {
        id: 'bld-01',
        name: 'Main Tower',
        address: 'Avenue Habib Bourguiba',
        floors: 12
      }
    ];

    const rooms: Room[] = [
      { id: 'room-101', buildingId: 'bld-01', roomNumber: '101', floor: 1, x: 5, y: 10, width: 40, height: 35, exit: 'Escalier A' },
      { id: 'room-102', buildingId: 'bld-01', roomNumber: '102', floor: 1, x: 55, y: 10, width: 40, height: 35, exit: 'Escalier B' },
      { id: 'room-201', buildingId: 'bld-01', roomNumber: '201', floor: 2, x: 5, y: 10, width: 40, height: 35, exit: 'Escalier A' }
    ];

    return buildings.map(building => this.toBuildingView(building, rooms));
  }
}