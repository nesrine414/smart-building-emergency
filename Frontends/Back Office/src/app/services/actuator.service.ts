import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export type ActuatorId =
  | 'VENTILATION'
  | 'SIREN'
  | 'GAS_VALVE'
  | 'EMERGENCY_EXIT';

export type ActuatorAction =
  | 'ON'
  | 'OFF'
  | 'OPEN'
  | 'CLOSE';

export interface ActuatorCommandResponse {
  status: string;
  roomId: string;
  actuator: ActuatorId;
  action: ActuatorAction;
}

@Injectable({ providedIn: 'root' })
export class ActuatorService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = environment.apiUrl;

  getStates(roomId: string): Observable<Record<string, string>> {
    return this.http.get<Record<string, string>>(
      `${this.apiUrl}/rooms/${encodeURIComponent(roomId)}/actuators`
    );
  }

  sendCommand(
    roomId: string,
    actuator: ActuatorId,
    action: ActuatorAction
  ): Observable<ActuatorCommandResponse> {
    return this.http.post<ActuatorCommandResponse>(
      `${this.apiUrl}/rooms/${encodeURIComponent(roomId)}/actuators`,
      { actuator, action }
    );
  }
}
