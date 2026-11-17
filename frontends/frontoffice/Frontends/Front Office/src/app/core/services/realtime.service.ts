import { Injectable, NgZone, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Subject, Subscription, BehaviorSubject, timer } from 'rxjs';
import { webSocket, WebSocketSubject } from 'rxjs/webSocket';

import { AuthService } from '../auth/auth.service';
import { Incident, SensorReading } from '../models';
import { environment } from '../../../environments/environment';

export interface RealtimeIncidentEvent {
  change: string;
  data: Incident;
}

export interface RealtimeActuatorEvent {
  data: Record<string, unknown>;
}

type RealtimeMessage =
  | { kind: 'reading'; data: SensorReading }
  | { kind: 'incident'; change: string; data: Incident }
  | { kind: 'actuator'; data: Record<string, unknown> };

@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private readonly zone = inject(NgZone);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly authService = inject(AuthService);

  private readonly readingsSubject = new Subject<SensorReading>();
  private readonly incidentsSubject = new Subject<RealtimeIncidentEvent>();
  private readonly actuatorsSubject = new Subject<RealtimeActuatorEvent>();
  private readonly connectedSubject = new BehaviorSubject<boolean>(false);

  readonly readings$ = this.readingsSubject.asObservable();
  readonly incidents$ = this.incidentsSubject.asObservable();
  readonly actuators$ = this.actuatorsSubject.asObservable();
  readonly connected$ = this.connectedSubject.asObservable();

  private socket?: WebSocketSubject<RealtimeMessage>;
  private socketSubscription?: Subscription;
  private reconnectSubscription?: Subscription;
  private reconnectDelay = 1000;
  private stopped = false;

  connect(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    this.stopped = false;

    if (this.socketSubscription && !this.socketSubscription.closed) return;

    const token = this.authService.getToken();
    if (!token) {
      console.warn('WebSocket : aucun JWT disponible.');
      return;
    }

    const separator = environment.wsUrl.includes('?') ? '&' : '?';
    const url = `${environment.wsUrl}${separator}token=${encodeURIComponent(token)}`;

    const socket = webSocket<RealtimeMessage>({
      url,
      deserializer: event => JSON.parse(event.data) as RealtimeMessage,
      openObserver: {
        next: () => this.zone.run(() => {
          this.reconnectDelay = 1000;
          this.connectedSubject.next(true);
          console.log('WebSocket connectÃƒÂ©.');
        })
      }
    });

    this.socket = socket;

    this.socketSubscription = socket.subscribe({
      next: message => this.zone.run(() => this.dispatch(message)),
      error: error => {
        console.error('WebSocket interrompu.', error);
        this.handleDisconnect(socket);
      },
      complete: () => this.handleDisconnect(socket)
    });
  }

  private dispatch(message: RealtimeMessage): void {
    switch (message.kind) {
      case 'reading':
        this.readingsSubject.next(message.data);
        break;
      case 'incident':
        this.incidentsSubject.next({
          change: message.change,
          data: message.data
        });
        break;
      case 'actuator':
        this.actuatorsSubject.next({ data: message.data });
        break;
      default:
        console.warn('Ãƒâ€°vÃƒÂ©nement WebSocket inconnu.', message);
    }
  }

  private handleDisconnect(socket: WebSocketSubject<RealtimeMessage>): void {
    if (this.socket !== socket) return;

    this.socket = undefined;
    this.socketSubscription = undefined;
    this.connectedSubject.next(false);

    if (!this.stopped) this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectSubscription) return;

    const delay = this.reconnectDelay;

    this.reconnectSubscription = timer(delay).subscribe(() => {
      this.reconnectSubscription = undefined;
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, 30000);
      this.connect();
    });
  }

  disconnect(): void {
    this.stopped = true;

    this.reconnectSubscription?.unsubscribe();
    this.reconnectSubscription = undefined;

    const socket = this.socket;
    this.socket = undefined;

    this.socketSubscription?.unsubscribe();
    this.socketSubscription = undefined;

    socket?.complete();
    this.connectedSubject.next(false);
  }
}