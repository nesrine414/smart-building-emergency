
import {
  Component,
  inject,
  AfterViewInit,
  OnDestroy,
  ViewChild,
  ElementRef,
  PLATFORM_ID
} from '@angular/core';

import { AsyncPipe, DatePipe, isPlatformBrowser } from '@angular/common';

import {
  interval,
  startWith,
  switchMap,
  shareReplay,
  Subscription,
  catchError,
  of
} from 'rxjs';

import { ReadingService } from './services/reading.service';
import { SensorReading } from './models/sensor-reading.model';

import { IncidentService } from './services/incident.service';
import { Incident } from './models/incident.model';

import { RealtimeService } from './services/realtime.service';

import { BuildingService } from './services/building.service';
import {
  Building,
  BuildingFloor,
  BuildingRoom
} from './models/building.model';

import { OccupantProfileComponent } from './components/occupant-profile/occupant-profile';

import { Chart, registerables } from 'chart.js';

Chart.register(...registerables);

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [AsyncPipe, DatePipe, OccupantProfileComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements AfterViewInit, OnDestroy {

  private readingService = inject(ReadingService);
  private incidentService = inject(IncidentService);
  private realtimeService = inject(RealtimeService);
  private buildingService = inject(BuildingService);
  private platformId = inject(PLATFORM_ID);

  // ==============================
  // INCIDENTS
  // ==============================

  incidents: Incident[] = [];
  incidentError = '';

  get openIncidentCount(): number {
    return this.incidents.filter(incident =>
      incident.status === 'OPEN' ||
      incident.status === 'ACKNOWLEDGED'
    ).length;
  }

  get hasActiveIncidents(): boolean {
    return this.openIncidentCount > 0;
  }

  private knownIncidentIds = new Set<string>();
  private initialIncidentsLoaded = false;

  // ==============================
  // SENSOR READINGS
  // ==============================

  currentReadings: SensorReading[] = [];

  get hasSafetyAlert(): boolean {
    return (
      this.hasActiveIncidents ||
      this.countAlerts(this.currentReadings) > 0
    );
  }

  readings$ = this.readingService.watchReadings().pipe(
    catchError(error => {
      console.error('Erreur lors de la récupération des mesures :', error);
      return of([]);
    }),
    shareReplay({ bufferSize: 1, refCount: true })
  );

  // ==============================
  // BUILDING MAP
  // ==============================

  building: Building | null = null;
  buildingError = '';
  selectedFloorId = '';

  get floors(): BuildingFloor[] {
    return this.building?.floors ?? [];
  }

  get selectedFloor(): BuildingFloor | undefined {
    return this.floors.find(
      floor => floor.id === this.selectedFloorId
    );
  }

  get selectedFloorRooms(): BuildingRoom[] {
    return this.selectedFloor?.rooms ?? [];
  }

  loadBuilding(): void {
    this.buildingService.getBuildings().subscribe({
      next: buildings => {
        this.building = buildings[0] ?? null;

        if (this.building) {
          this.selectedFloorId =
            this.building.floors[0]?.id ?? '';
        }

        this.buildingError = '';
        console.log('Bâtiment chargé :', this.building);
      },
      error: error => {
        console.error('Erreur de chargement du bâtiment :', error);
        this.buildingError = 'Impossible de charger le bâtiment.';
      }
    });
  }

  selectFloor(floorId: string): void {
    this.selectedFloorId = floorId;
  }

  isRoomAffected(roomId: string): boolean {
    const hasActiveIncident = this.incidents.some(incident => {
      const active =
        incident.status === 'OPEN' ||
        incident.status === 'ACKNOWLEDGED';

      return active && (
        incident.roomId === roomId ||
        incident.affectedRoomIds?.includes(roomId)
      );
    });

    const hasGasAlert = this.getLatestReadingsBySensor(
      this.currentReadings
    ).some(reading =>
      reading.roomId === roomId &&
      this.isGasAlert(reading)
    );

    return hasActiveIncident || hasGasAlert;
  }

  getRoomIncidents(roomId: string): Incident[] {
    return this.incidents.filter(incident => {
      const active =
        incident.status === 'OPEN' ||
        incident.status === 'ACKNOWLEDGED';

      return active && (
        incident.roomId === roomId ||
        incident.affectedRoomIds?.includes(roomId)
      );
    });
  }

  getRoomStatusClass(room: BuildingRoom): string {
    if (room.type === 'EXIT') return 'room-exit';
    if (room.type === 'HALLWAY') return 'room-hallway';
    if (room.type === 'STAIRCASE') return 'room-staircase';

    return this.isRoomAffected(room.id)
      ? 'room-danger'
      : 'room-safe';
  }

  getAffectedRoomCount(): number {
    return this.selectedFloorRooms.filter(
      room => this.isRoomAffected(room.id)
    ).length;
  }

  // ==============================
  // CHARTS
  // ==============================

  @ViewChild('gasCanvas')
  gasCanvas?: ElementRef<HTMLCanvasElement>;

  @ViewChild('temperatureCanvas')
  temperatureCanvas?: ElementRef<HTMLCanvasElement>;

  gasChart: Chart | null = null;
  temperatureChart: Chart | null = null;

  // ==============================
  // SUBSCRIPTIONS
  // ==============================

  private readingsSubscription?: Subscription;
  private realtimeSubscription?: Subscription;

  // ==============================
  // LOAD INCIDENTS
  // ==============================

  loadIncidents(notifyNew = false): void {
    this.incidentService.getIncidents().subscribe({
      next: data => {
        console.log('Incidents récupérés depuis API :', data);

        if (!this.initialIncidentsLoaded) {
          data.forEach(incident => {
            this.knownIncidentIds.add(incident.id);
          });

          this.initialIncidentsLoaded = true;
        } else if (notifyNew) {
          data.forEach(incident => {
            if (!this.knownIncidentIds.has(incident.id)) {
              console.log('NOUVEL INCIDENT DÉTECTÉ :', incident);
              this.showIncidentNotification(incident);
              this.knownIncidentIds.add(incident.id);
            }
          });
        }

        this.incidents = data;
        this.incidentError = '';
      },
      error: error => {
        console.error('Erreur de chargement des incidents :', error);
        this.incidentError = 'Impossible de charger les incidents.';
      }
    });
  }

  // ==============================
  // BROWSER NOTIFICATIONS
  // ==============================

  private showIncidentNotification(incident: Incident): void {
    if (!isPlatformBrowser(this.platformId)) return;

    if (!('Notification' in window)) {
      console.error('API Notification non disponible.');
      return;
    }

    if (Notification.permission !== 'granted') {
      console.warn('Autorisation de notification non accordée.');
      return;
    }

    try {
      const title = incident.type === 'GAS_LEAK'
        ? '🚨 Nouvelle fuite de gaz'
        : '🔥 Nouvel incident incendie';

      const notification = new Notification(title, {
        body: `${incident.description}\nGravité : ${incident.severity}`,
        icon: '/favicon.ico',
        tag: `incident-${incident.id}`,
        requireInteraction: true
      });

      notification.onclick = () => {
        window.focus();
        notification.close();
      };

    } catch (error) {
      console.error('Impossible de créer la notification :', error);
    }
  }

  // ==============================
  // API STATUS
  // ==============================

  apiOnline$ = interval(5000).pipe(
    startWith(0),
    switchMap(() =>
      this.readingService.checkApiStatus().pipe(
        catchError(error => {
          console.error('API indisponible :', error);
          return of(false);
        })
      )
    ),
    shareReplay({ bufferSize: 1, refCount: true })
  );

  // ==============================
  // INITIALIZATION
  // ==============================

  ngAfterViewInit(): void {
    this.loadBuilding();
    this.loadIncidents();

    this.readingsSubscription = this.readings$.subscribe(readings => {
      this.currentReadings = readings;
      this.updateHistoryCharts(readings);
    });

    this.realtimeService.connect();

    this.realtimeSubscription =
      this.realtimeService.events$.subscribe(event => {
        console.log('Événement WebSocket reçu :', event);
        this.loadIncidents(true);
      });
  }

  // ==============================
  // TIMESTAMP
  // ==============================

  private toMilliseconds(timestamp: number): number {
    return timestamp < 1000000000000
      ? timestamp * 1000
      : timestamp;
  }

  // ==============================
  // LATEST READINGS
  // ==============================

  private getLatestReadingsBySensor(
    readings: SensorReading[]
  ): SensorReading[] {

    const latestBySensor = new Map<string, SensorReading>();

    for (const reading of readings) {
      if (!reading.sensorId) continue;

      const current = latestBySensor.get(reading.sensorId);

      if (
        !current ||
        this.toMilliseconds(reading.timestamp) >
        this.toMilliseconds(current.timestamp)
      ) {
        latestBySensor.set(reading.sensorId, reading);
      }
    }

    return Array.from(latestBySensor.values());
  }

  // ==============================
  // HISTORY CHARTS
  // ==============================

  updateHistoryCharts(readings: SensorReading[]): void {
    if (!this.gasCanvas || !this.temperatureCanvas) return;

    this.updateOneChart(
      this.gasCanvas.nativeElement,
      readings,
      'GAS',
      'Gas concentration (ppm)',
      '#ef4444',
      'gas'
    );

    this.updateOneChart(
      this.temperatureCanvas.nativeElement,
      readings,
      'TEMPERATURE',
      'Temperature (°C)',
      '#f59e0b',
      'temperature'
    );
  }

  updateOneChart(
    canvas: HTMLCanvasElement,
    readings: SensorReading[],
    sensorType: string,
    label: string,
    color: string,
    chartType: 'gas' | 'temperature'
  ): void {

    const history = readings
      .filter(reading =>
        reading.type === sensorType &&
        Number.isFinite(reading.timestamp) &&
        Number.isFinite(reading.value)
      )
      .slice()
      .sort((a, b) =>
        this.toMilliseconds(a.timestamp) -
        this.toMilliseconds(b.timestamp)
      );

    const labels = history.map(reading =>
      new Date(this.toMilliseconds(reading.timestamp))
        .toLocaleTimeString()
    );

    const values = history.map(reading => reading.value);

    let chart = chartType === 'gas'
      ? this.gasChart
      : this.temperatureChart;

    if (!chart) {
      chart = new Chart(canvas, {
        type: 'line',
        data: {
          labels,
          datasets: [{
            label,
            data: values,
            borderColor: color,
            backgroundColor: color + '33',
            borderWidth: 2,
            pointRadius: 3,
            pointHoverRadius: 5,
            fill: true,
            tension: 0.3
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: false,
          plugins: {
            legend: {
              display: true,
              labels: { color: '#cbd5e1' }
            }
          },
          scales: {
            x: {
              ticks: {
                color: '#94a3b8',
                maxTicksLimit: 8
              },
              grid: {
                color: 'rgba(148, 163, 184, 0.12)'
              },
              title: {
                display: true,
                text: 'Time',
                color: '#94a3b8'
              }
            },
            y: {
              beginAtZero: true,
              ticks: { color: '#94a3b8' },
              grid: {
                color: 'rgba(148, 163, 184, 0.12)'
              },
              title: {
                display: true,
                text: label,
                color: '#94a3b8'
              }
            }
          }
        }
      });

      if (chartType === 'gas') {
        this.gasChart = chart;
      } else {
        this.temperatureChart = chart;
      }

    } else {
      chart.data.labels = labels;
      chart.data.datasets[0].data = values;
      chart.update('none');
    }
  }

  // ==============================
  // GAS ALERTS
  // ==============================

  isGasAlert(reading: SensorReading): boolean {
    return reading.type === 'GAS' && reading.value > 1000;
  }

  countAlerts(readings: SensorReading[]): number {
    return this.getLatestReadingsBySensor(readings).filter(
      reading => this.isGasAlert(reading)
    ).length;
  }

  getLatestAlerts(readings: SensorReading[]): SensorReading[] {
    return this.getLatestReadingsBySensor(readings).filter(
      reading => this.isGasAlert(reading)
    );
  }

  // ==============================
  // SENSOR COUNTS
  // ==============================

  countUniqueSensors(readings: SensorReading[]): number {
    return new Set(
      readings
        .filter(reading => reading.sensorId)
        .map(reading => reading.sensorId)
    ).size;
  }

  countActiveSensors(readings: SensorReading[]): number {
    // Un capteur est considéré actif dès qu'une mesure valide
    // est présente dans le flux de mesures reçu.
    return this.getLatestReadingsBySensor(readings).filter(
      reading =>
        !!reading.sensorId &&
        Number.isFinite(reading.timestamp) &&
        Number.isFinite(reading.value)
    ).length;
  }

  // ==============================
  // CURRENT VALUES
  // ==============================

  getCurrentValue(readings: SensorReading[], type: string): number {
    const latest = readings
      .filter(reading => reading.type === type)
      .slice()
      .sort((a, b) =>
        this.toMilliseconds(b.timestamp) -
        this.toMilliseconds(a.timestamp)
      )[0];

    return latest?.value ?? 0;
  }

  getCurrentUnit(readings: SensorReading[], type: string): string {
    const latest = readings
      .filter(reading => reading.type === type)
      .slice()
      .sort((a, b) =>
        this.toMilliseconds(b.timestamp) -
        this.toMilliseconds(a.timestamp)
      )[0];

    return latest?.unit ?? '';
  }

  getBarWidth(readings: SensorReading[], type: string): number {
    const value = this.getCurrentValue(readings, type);
    const maximum = type === 'GAS' ? 2000 : 50;

    return Math.min(100, Math.max(0, (value / maximum) * 100));
  }

  // ==============================
  // SORT READINGS
  // ==============================

  getSortedReadings(readings: SensorReading[]): SensorReading[] {
    return [...readings].sort((a, b) =>
      this.toMilliseconds(b.timestamp) -
      this.toMilliseconds(a.timestamp)
    );
  }

  trackReading(index: number, reading: SensorReading): string {
    return reading.id ||
      `${reading.sensorId}-${reading.timestamp}-${index}`;
  }

  // ==============================
  // INCIDENT ACTIONS
  // ==============================

  acknowledgeIncident(id: string): void {
    this.incidentService.updateStatus(id, 'ACKNOWLEDGED').subscribe({
      next: () => this.loadIncidents(),
      error: error => {
        console.error('Erreur lors de l’acquittement :', error);
        this.incidentError = 'Impossible d’acquitter cet incident.';
      }
    });
  }

  resolveIncident(id: string): void {
    this.incidentService.updateStatus(id, 'RESOLVED').subscribe({
      next: () => this.loadIncidents(),
      error: error => {
        console.error('Erreur lors de la résolution :', error);
        this.incidentError = 'Impossible de résoudre cet incident.';
      }
    });
  }

  // ==============================
  // CLEANUP
  // ==============================

  ngOnDestroy(): void {
    this.readingsSubscription?.unsubscribe();
    this.realtimeSubscription?.unsubscribe();

    this.realtimeService.disconnect();

    this.gasChart?.destroy();
    this.temperatureChart?.destroy();
  }
}
