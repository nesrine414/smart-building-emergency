import {
  Component,
  inject,
  AfterViewInit,
  OnDestroy,
  ViewChild,
  ElementRef
} from '@angular/core';

import { AsyncPipe, DatePipe } from '@angular/common';

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

import { Chart, registerables } from 'chart.js';

Chart.register(...registerables);

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [AsyncPipe, DatePipe],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements AfterViewInit, OnDestroy {

  private readingService = inject(ReadingService);

  @ViewChild('gasCanvas')
  gasCanvas?: ElementRef<HTMLCanvasElement>;

  @ViewChild('temperatureCanvas')
  temperatureCanvas?: ElementRef<HTMLCanvasElement>;

  gasChart: Chart | null = null;
  temperatureChart: Chart | null = null;

  private readingsSubscription?: Subscription;

  // Actualisation automatique des mesures toutes les 5 secondes
  readings$ = this.readingService.watchReadings().pipe(
    catchError(error => {
      console.error('Erreur lors de la récupération des mesures :', error);
      return of([]);
    }),
    shareReplay({ bufferSize: 1, refCount: true })
  );

  // Vérification de la connexion à l'API toutes les 5 secondes
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

  // Initialiser les graphiques après le chargement de la vue
  ngAfterViewInit(): void {
    this.readingsSubscription = this.readings$.subscribe(readings => {
      this.updateHistoryCharts(readings);
    });
  }

  // Convertir un timestamp en millisecondes
  private toMilliseconds(timestamp: number): number {
    return timestamp < 1000000000000
      ? timestamp * 1000
      : timestamp;
  }

  // Récupérer la dernière mesure de chaque capteur
  private getLatestReadingsBySensor(
    readings: SensorReading[]
  ): SensorReading[] {

    const latestBySensor = new Map<string, SensorReading>();

    for (const reading of readings) {
      if (!reading.sensorId) {
        continue;
      }

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

  // Créer ou actualiser les graphiques historiques
  updateHistoryCharts(readings: SensorReading[]): void {

    if (!this.gasCanvas || !this.temperatureCanvas) {
      return;
    }

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

  // Mettre à jour un graphique
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
              labels: {
                color: '#cbd5e1'
              }
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
              ticks: {
                color: '#94a3b8'
              },
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

  // Détecter une alerte gaz supérieure à 1000 ppm
  isGasAlert(reading: SensorReading): boolean {
    return reading.type === 'GAS' && reading.value > 1000;
  }

  // Compter les alertes actuelles
  countAlerts(readings: SensorReading[]): number {
    return this.getLatestReadingsBySensor(readings).filter(
      reading => this.isGasAlert(reading)
    ).length;
  }

  // Compter les capteurs distincts
  countUniqueSensors(readings: SensorReading[]): number {
    return new Set(
      readings
        .filter(reading => reading.sensorId)
        .map(reading => reading.sensorId)
    ).size;
  }

  // Compter les capteurs actifs ayant envoyé une mesure récente
  countActiveSensors(readings: SensorReading[]): number {
    const now = Date.now();

    const latest = this.getLatestReadingsBySensor(readings);

    console.table(latest.map(reading => ({
      sensor: reading.sensorId,
      timestamp: this.toMilliseconds(reading.timestamp),
      ageSeconds: Math.round(
        (now - this.toMilliseconds(reading.timestamp)) / 1000
      ),
      active: now - this.toMilliseconds(reading.timestamp) >= 0 &&
              now - this.toMilliseconds(reading.timestamp) <= 60000
    })));

    return latest.filter(reading => {
      const age = now - this.toMilliseconds(reading.timestamp);
      return age >= 0 && age <= 60000;
    }).length;
  }

  // Récupérer la mesure la plus récente d'un type donné
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

  // Récupérer l'unité de la mesure la plus récente
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

  // Calculer la largeur de la barre de progression
  getBarWidth(readings: SensorReading[], type: string): number {

    const value = this.getCurrentValue(readings, type);
    const maximum = type === 'GAS' ? 2000 : 50;

    return Math.min(100, Math.max(0, (value / maximum) * 100));
  }

  // Récupérer uniquement les alertes encore actives
  getLatestAlerts(readings: SensorReading[]): SensorReading[] {
    const latestBySensor = new Map<string, SensorReading>();

    for (const reading of readings) {
      const key = `${reading.sensorId}-${reading.type}`;
      const previous = latestBySensor.get(key);

      if (!previous || reading.timestamp > previous.timestamp) {
        latestBySensor.set(key, reading);
      }
    }

    return Array.from(latestBySensor.values()).filter(
      reading => reading.type === 'GAS' && reading.value > 1000
    );
  }

  // Trier les mesures de la plus récente à la plus ancienne
  getSortedReadings(readings: SensorReading[]): SensorReading[] {

    return [...readings].sort((a, b) =>
      this.toMilliseconds(b.timestamp) -
      this.toMilliseconds(a.timestamp)
    );
  }

  // Identifier chaque mesure dans la liste
  trackReading(index: number, reading: SensorReading): string {
    return reading.id ||
      `${reading.sensorId}-${reading.timestamp}-${index}`;
  }

  // Nettoyer les graphiques et l'abonnement
  ngOnDestroy(): void {

    this.readingsSubscription?.unsubscribe();

    this.gasChart?.destroy();
    this.temperatureChart?.destroy();
  }
}