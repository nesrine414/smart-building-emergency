import { Component, signal, computed, inject } from '@angular/core';
import { BuildingService } from '../../core/services/building.service';
import { AlertService } from '../../core/services/alert.service';
import { BuildingView, Floor } from '../../core/models';

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

  protected readonly selectedBuildingId = signal<string>('bld-01');
  protected readonly selectedFloorLevel = signal<number>(1);

  protected readonly selectedBuilding = computed<BuildingView | undefined>(() =>
    this.buildings().find(b => b.id === this.selectedBuildingId())
  );

  protected readonly selectedFloor = computed(() =>
    this.selectedBuilding()?.floors.find(
      floor => floor.level === this.selectedFloorLevel()
    )
  );

  protected readonly floorAlerts = computed(() => {
    const floor = this.selectedFloor();
    if (!floor) return [];

    const roomIds = new Set(floor.rooms.map(room => room.id));

    return this.alerts().filter(alert =>
      alert.buildingId === this.selectedBuildingId() &&
      alert.status === 'ACTIVE' &&
      !!alert.roomId &&
      roomIds.has(alert.roomId)
    );
  });

  selectBuilding(id: string): void {
    this.selectedBuildingId.set(id);

    const building = this.buildings().find(item => item.id === id);
    if (building?.floors.length) {
      this.selectedFloorLevel.set(building.floors[0].level);
    }
  }

  selectFloor(level: number): void {
    this.selectedFloorLevel.set(level);
  }

  getRoomStatusClass(roomId: string): string {
    const roomAlerts = this.alerts().filter(
      alert => alert.roomId === roomId && alert.status === 'ACTIVE'
    );

    if (roomAlerts.length === 0) return 'room-safe';

    return roomAlerts.some(alert => alert.severity === 'CRITICAL')
      ? 'room-critical'
      : 'room-warning';
  }

  getRoomAlerts(roomId: string) {
    return this.alerts().filter(
      alert => alert.roomId === roomId && alert.status === 'ACTIVE'
    );
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