import {
  Component,
  computed,
  effect,
  inject,
  PLATFORM_ID,
  signal
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { AuthService } from '../../core/auth/auth.service';
import { BuildingService } from '../../core/services/building.service';
import { PushNotificationService } from '../../core/services/push-notification.service';
import { BuildingView, Floor, RoomView } from '../../core/models';

interface LocationPreference {
  buildingId: string;
  floorLevel: number | null;
  roomId: string;
}

@Component({
  selector: 'app-profile',
  templateUrl: './profile.html',
  styleUrl: './profile.scss'
})
export class Profile {
  private readonly authService = inject(AuthService);
  private readonly buildingService = inject(BuildingService);
  private readonly pushService = inject(PushNotificationService);
  private readonly platformId = inject(PLATFORM_ID);

  protected readonly user = this.authService.user;
  protected readonly buildings = this.buildingService.buildings;
  protected readonly pushSupported = this.pushService.isSupported;
  protected readonly pushSubscribed = this.pushService.isSubscribed;
  protected readonly pushBusy = this.pushService.isBusy;
  protected readonly pushError = this.pushService.errorMessage;
  protected readonly pushSuccess = this.pushService.successMessage;
  protected readonly locationError = signal<string | null>(null);

  protected readonly selectedBuildingId = signal('');
  protected readonly selectedFloorLevel = signal<number | null>(null);
  protected readonly selectedRoomId = signal('');

  protected readonly selectedBuilding = computed<BuildingView | undefined>(() =>
    this.buildings().find(item => item.id === this.selectedBuildingId())
  );

  protected readonly floors = computed<Floor[]>(() =>
    this.selectedBuilding()?.floors ?? []
  );

  protected readonly selectedFloor = computed<Floor | undefined>(() =>
    this.floors().find(floor => floor.level === this.selectedFloorLevel())
  );

  protected readonly rooms = computed<RoomView[]>(() =>
    this.selectedFloor()?.rooms ?? []
  );

  protected readonly selectedRoom = computed<RoomView | undefined>(() =>
    this.rooms().find(room => room.id === this.selectedRoomId())
  );

  private initialized = false;

  constructor() {
    effect(() => {
      const availableBuildings = this.buildings();

      if (!availableBuildings.length || this.initialized) {
        return;
      }

      this.initialized = true;
      const saved = this.readPreference();

      const building =
        availableBuildings.find(item => item.id === saved?.buildingId) ??
        availableBuildings[0];

      this.selectedBuildingId.set(building.id);

      const floor =
        building.floors.find(item => item.level === saved?.floorLevel) ??
        building.floors.find(item => item.rooms.length > 0) ??
        building.floors[0];

      if (floor) {
        this.selectedFloorLevel.set(floor.level);

        const room = floor.rooms.find(item => item.id === saved?.roomId);
        this.selectedRoomId.set(room?.id ?? '');
      }
    });
  }

  selectBuilding(id: string): void {
    const building = this.buildings().find(item => item.id === id);
    if (!building) return;

    this.selectedBuildingId.set(id);

    const floor =
      building.floors.find(item => item.rooms.length > 0) ??
      building.floors[0];

    this.selectedFloorLevel.set(floor?.level ?? null);
    this.selectedRoomId.set('');
    this.savePreference();
  }

  selectFloor(value: string): void {
    const level = Number(value);
    const floor = this.floors().find(item => item.level === level);
    if (!floor) return;

    this.selectedFloorLevel.set(level);
    this.selectedRoomId.set('');
    this.savePreference();
  }

  selectRoom(id: string): void {
    if (!this.rooms().some(room => room.id === id)) return;

    this.selectedRoomId.set(id);
    this.savePreference();
  }

  async enablePush(): Promise<void> {
    const floor = this.selectedFloorLevel();

    if (!this.selectedBuildingId() || floor === null || !this.selectedRoomId()) {
      this.locationError.set(
        'Sélectionnez un bâtiment, un étage et une salle avant de continuer.'
      );
      return;
    }

    this.locationError.set(null);

    await this.pushService.enable({
      buildingId: this.selectedBuildingId(),
      floor,
      roomId: this.selectedRoomId()
    });
  }

  async disablePush(): Promise<void> {
    await this.pushService.disable();
  }
  private readPreference(): LocationPreference | null {
    if (!isPlatformBrowser(this.platformId)) return null;

    try {
      const raw = localStorage.getItem('smart-building-location-preference');
      return raw ? JSON.parse(raw) as LocationPreference : null;
    } catch {
      return null;
    }
  }

  private savePreference(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    const preference: LocationPreference = {
      buildingId: this.selectedBuildingId(),
      floorLevel: this.selectedFloorLevel(),
      roomId: this.selectedRoomId()
    };

    try {
      localStorage.setItem(
        'smart-building-location-preference',
        JSON.stringify(preference)
      );
    } catch (error) {
      console.warn('Unable to save local building preference.', error);
    }
  }

  logout(): void {
    this.authService.logout();
  }
}