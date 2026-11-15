import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { tap } from 'rxjs/operators';

import { SensorReading } from '../models';
import { RealtimeService } from './realtime.service';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ReadingService {
  private readonly http = inject(HttpClient);
  private readonly realtime = inject(RealtimeService);

  private readonly readingList = signal<SensorReading[]>([]);
  readonly readings = this.readingList.asReadonly();

  readonly latestReadings = computed(() => {
    const latest = new Map<string, SensorReading>();

    for (const reading of this.readingList()) {
      const previous = latest.get(reading.sensorId);

      if (!previous || reading.timestamp >= previous.timestamp) {
        latest.set(reading.sensorId, reading);
      }
    }

    return Array.from(latest.values()).sort((a, b) =>
      a.roomId.localeCompare(b.roomId) ||
      a.type.localeCompare(b.type)
    );
  });

  constructor() {
    this.refresh().subscribe({
      error: error => console.error('Chargement des mesures impossible.', error)
    });

    this.realtime.readings$.subscribe(reading => {
      this.upsert(reading);
    });
  }

  refresh(): Observable<SensorReading[]> {
    const request = environment.useMock
      ? of(this.getMockReadings())
      : this.http.get<SensorReading[]>(
          `${environment.apiUrl}/readings?limit=100`
        );

    return request.pipe(
      tap(readings => this.replaceLatest(readings))
    );
  }

  private replaceLatest(readings: SensorReading[]): void {
    this.readingList.set(readings);
  }

  private upsert(reading: SensorReading): void {
    this.readingList.update(current => {
      const previous = current.find(
        item => item.sensorId === reading.sensorId
      );

      if (previous && previous.timestamp > reading.timestamp) {
        return current;
      }

      return [
        ...current.filter(item => item.sensorId !== reading.sensorId),
        reading
      ];
    });
  }

  private getMockReadings(): SensorReading[] {
    const now = Date.now();

    return [
      {
        id: 'mock-gas-101',
        buildingId: 'bld-01',
        roomId: 'room-101',
        sensorId: 'gas-101',
        type: 'GAS',
        timestamp: now,
        value: 420,
        unit: 'ppm'
      },
      {
        id: 'mock-temp-101',
        buildingId: 'bld-01',
        roomId: 'room-101',
        sensorId: 'temp-101',
        type: 'TEMPERATURE',
        timestamp: now,
        value: 24,
        unit: 'C'
      },
      {
        id: 'mock-hum-101',
        buildingId: 'bld-01',
        roomId: 'room-101',
        sensorId: 'hum-101',
        type: 'HUMIDITY',
        timestamp: now,
        value: 40,
        unit: '%'
      }
    ];
  }
}