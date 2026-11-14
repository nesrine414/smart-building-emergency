
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import {
  Observable,
  timer,
  switchMap,
  map,
  catchError,
  of
} from 'rxjs';

import { SensorReading } from '../models/sensor-reading.model';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class ReadingService {
  private http = inject(HttpClient);

  // Récupérer les mesures des capteurs
  getReadings(): Observable<SensorReading[]> {
    if (environment.useMock) {
      return this.http.get<SensorReading[]>('/mock/readings.json');
    }

    return this.http.get<SensorReading[]>(
      `${environment.apiUrl}/readings?limit=50`
    );
  }

  // Actualiser les mesures toutes les 5 secondes
  watchReadings(): Observable<SensorReading[]> {
    return timer(0, 5000).pipe(
      switchMap(() => this.getReadings())
    );
  }

  // Vérifier si l'API est accessible
  checkApiStatus(): Observable<boolean> {
    return this.http
      .get<SensorReading[]>(
        `${environment.apiUrl}/readings?limit=1`
      )
      .pipe(
        map(() => true),
        catchError(() => of(false))
      );
  }
}
