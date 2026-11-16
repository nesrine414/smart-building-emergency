import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, map, tap, throwError } from 'rxjs';

import { Incident } from '../models';
import { RealtimeService } from './realtime.service';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class IncidentService {
  private readonly http = inject(HttpClient);
  private readonly realtime = inject(RealtimeService);
  private readonly apiUrl = `${environment.apiUrl}/incidents`;

  private readonly incidentList = signal<Incident[]>([]);
  readonly incidents = this.incidentList.asReadonly();

  constructor() {
    this.refresh().subscribe({
      error: error => console.error('Chargement incidents impossible.', error)
    });

    this.realtime.incidents$.subscribe(event => {
      const updated = this.normalize(event.data);

      this.incidentList.update(items => {
        const exists = items.some(item => item.id === updated.id);

        return exists
          ? items.map(item => item.id === updated.id ? updated : item)
          : [updated, ...items];
      });
    });
  }

  refresh(): Observable<Incident[]> {
    const request = environment.useMock
      ? of(this.getMockIncidents())
      : this.http.get<Incident[]>(this.apiUrl);

    return request.pipe(
      map(items => items.map(item => this.normalize(item))),
      tap(items => this.incidentList.set(items))
    );
  }

  getIncidents(status?: string): Observable<Incident[]> {
    return this.refresh().pipe(
      map(items => status ? items.filter(item => item.status === status) : items)
    );
  }

  fetchIncidentById(id: string): Observable<Incident> {
    if (environment.useMock) {
      const found = this.incidentList().find(item => item.id === id);

      return found
        ? of(found)
        : throwError(() => new Error('Incident introuvable.'));
    }

    return this.http.get<Incident>(
      `${this.apiUrl}/${encodeURIComponent(id)}`
    ).pipe(map(item => this.normalize(item)));
  }

  getIncidentById(id: string): Incident | undefined {
    return this.incidentList().find(item => item.id === id);
  }

  getActiveIncidents(): Incident[] {
    return this.incidentList().filter(item => item.status !== 'RESOLVED');
  }

  updateStatus(
    id: string,
    status: 'ACKNOWLEDGED' | 'RESOLVED'
  ): Observable<Incident> {
    if (environment.useMock) {
      const current = this.getIncidentById(id);

      if (!current) {
        return throwError(() => new Error('Incident introuvable.'));
      }

      const updated = this.normalize({
        ...current,
        status,
        updatedAt: Date.now()
      });

      this.replaceIncident(updated);
      return of(updated);
    }

    return this.http.patch<Incident>(
      `${this.apiUrl}/${encodeURIComponent(id)}`,
      { status }
    ).pipe(
      map(item => this.normalize(item)),
      tap(item => this.replaceIncident(item))
    );
  }

  private replaceIncident(updated: Incident): void {
    this.incidentList.update(items =>
      items.map(item => item.id === updated.id ? updated : item)
    );
  }

  private normalize(item: Incident): Incident {
    const timestamp = Number(item.timestamp || Date.now());

    return {
      ...item,
      timestamp,
      updatedAt: Number(item.updatedAt || timestamp),
      affectedRoomIds: item.affectedRoomIds ?? [],
      title: item.title ?? item.type.replace(/_/g, ' '),
      priority: item.severity,
      buildingName: item.buildingId,
      roomName: item.roomId,
      reportedBy: 'Smart Building System',
      reportedAt: timestamp
    };
  }

  private getMockIncidents(): Incident[] {
    const now = Date.now();

    return [{
      id: 'mock-gas-101',
      buildingId: 'bld-01',
      roomId: 'room-101',
      sensorId: 'gas-101',
      type: 'GAS_LEAK',
      severity: 'CRITICAL',
      status: 'OPEN',
      description: 'Simulation de fuite de gaz dans la salle 101.',
      occupied: true,
      timestamp: now,
      updatedAt: now,
      affectedRoomIds: ['room-101'],
      recommendation: 'Quitter la zone et suivre la sortie conseillée.'
    }];
  }
}