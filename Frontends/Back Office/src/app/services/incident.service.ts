
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

import { Incident } from '../models/incident.model';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class IncidentService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/incidents`;

  // Récupérer tous les incidents ou filtrer par statut
  getIncidents(status?: string): Observable<Incident[]> {
    if (environment.useMock) {
      return this.http.get<Incident[]>('/mock/incidents.json').pipe(
        map(incidents =>
          status
            ? incidents.filter(incident => incident.status === status)
            : incidents
        )
      );
    }

    const url = status
      ? `${this.apiUrl}?status=${encodeURIComponent(status)}`
      : this.apiUrl;

    return this.http.get<Incident[]>(url);
  }

  // Récupérer un incident par son identifiant
  getIncidentById(id: string): Observable<Incident> {
    if (environment.useMock) {
      return this.getIncidents().pipe(
        map(incidents => {
          const incident = incidents.find(item => item.id === id);

          if (!incident) {
            throw new Error('Incident introuvable');
          }

          return incident;
        })
      );
    }

    return this.http.get<Incident>(
      `${this.apiUrl}/${encodeURIComponent(id)}`
    );
  }

  // Modifier le statut d'un incident
  updateStatus(
    id: string,
    status: 'ACKNOWLEDGED' | 'RESOLVED'
  ): Observable<Incident> {
    return this.http.patch<Incident>(
      `${this.apiUrl}/${encodeURIComponent(id)}`,
      { status }
    );
  }
}
