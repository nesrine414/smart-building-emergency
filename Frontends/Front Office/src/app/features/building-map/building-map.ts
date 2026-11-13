import { Component, signal, computed, inject } from '@angular/core';
import { BuildingService } from '../../core/services/building.service';
import { AlertService } from '../../core/services/alert.service';
import { Building, Floor } from '../../core/models';

@Component({
  selector: 'app-building-map',
  templateUrl: './building-map.html',
  styleUrl: './building-map.scss'
})
export class BuildingMap {

  private readonly buildingService = inject(BuildingService);
  private readonly alertService = inject(AlertService);

  protected readonly buildings = this.buildingService.buildings;
  protected readonly alerts = this.alertService.alerts;

  protected readonly selectedBuildingId = signal<string>('b-001');
  protected readonly selectedFloorLevel = signal<number>(3);

  protected readonly selectedBuilding = computed(() =>
    this.buildings().find(b => b.id === this.selectedBuildingId())
  );

  protected readonly selectedFloor = computed(() =>
    this.selectedBuilding()?.floors.find(f => f.level === this.selectedFloorLevel())
  );

  protected readonly floorAlerts = computed(() => {
    const floor = this.selectedFloor();
    if (!floor) return [];
    return this.alerts().filter(a =>
      a.buildingId === this.selectedBuildingId() &&
      a.floorId === floor.id &&
      a.status === 'ACTIVE'
    );
  });

  selectBuilding(id: string): void {
    this.selectedBuildingId.set(id);
    const building = this.buildings().find(b => b.id === id);
    if (building && building.floors.length > 0) {
      this.selectedFloorLevel.set(building.floors[0].level);
    }
  }

  selectFloor(level: number): void {
    this.selectedFloorLevel.set(level);
  }

  getRoomStatusClass(roomId: string): string {
    const hasAlert = this.alerts().some(a =>
      a.roomId === roomId && a.status === 'ACTIVE'
    );
    if (hasAlert) {
      const critical = this.alerts().some(a =>
        a.roomId === roomId && a.severity === 'CRITICAL' && a.status === 'ACTIVE'
      );
      return critical ? 'room-critical' : 'room-warning';
    }
    return 'room-safe';
  }

  getRoomAlerts(roomId: string) {
    return this.alerts().filter(a => a.roomId === roomId && a.status === 'ACTIVE');
  }

  getAlertIcon(type: string): string {
    switch (type) {
      case 'FIRE': return '🔥';
      case 'GAS_LEAK': return '💨';
      case 'SMOKE': return '🌫️';
      default: return '⚠️';
    }
  }
}
