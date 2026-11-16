
import { Injectable, NgZone, OnDestroy, inject, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Observable, Subject, timer, Subscription } from 'rxjs';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class RealtimeService implements OnDestroy {

  private zone = inject(NgZone);
  private platformId = inject(PLATFORM_ID);

  private eventsSubject = new Subject<any>();
  private socket: WebSocket | null = null;
  private reconnectSubscription?: Subscription;
  private reconnectDelay = 3000;
  private destroyed = false;

  events$: Observable<any> = this.eventsSubject.asObservable();

  connect(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    if (
      this.destroyed ||
      this.socket?.readyState === WebSocket.OPEN ||
      this.socket?.readyState === WebSocket.CONNECTING
    ) {
      return;
    }

    const wsUrl = environment.apiUrl
      .replace(/^http:/, 'ws:')
      .replace(/^https:/, 'wss:')
      + '/ws/events';

    try {
      this.socket = new WebSocket(wsUrl);

      this.socket.onopen = () => {
        this.reconnectDelay = 3000;
        console.log('WebSocket connecté');
      };

      this.socket.onmessage = (message) => {
        this.zone.run(() => {
          try {
            this.eventsSubject.next(JSON.parse(message.data));
          } catch {
            console.error('Message WebSocket invalide :', message.data);
          }
        });
      };

      this.socket.onerror = (error) => {
        console.error('Erreur WebSocket :', error);
      };

      this.socket.onclose = () => {
        this.socket = null;

        if (!this.destroyed) {
          this.scheduleReconnect();
        }
      };

    } catch (error) {
      console.error('Erreur de connexion WebSocket :', error);
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect(): void {
    if (this.destroyed || this.reconnectSubscription) {
      return;
    }

    this.reconnectSubscription = timer(this.reconnectDelay).subscribe(() => {
      this.reconnectSubscription = undefined;
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, 30000);
      this.connect();
    });
  }

  disconnect(): void {
    this.destroyed = true;

    this.reconnectSubscription?.unsubscribe();
    this.reconnectSubscription = undefined;

    const socket = this.socket;
    this.socket = null;

    if (
      socket &&
      (socket.readyState === WebSocket.OPEN ||
       socket.readyState === WebSocket.CONNECTING)
    ) {
      socket.close();
    }
  }

  ngOnDestroy(): void {
    this.disconnect();
    this.eventsSubject.complete();
  }
}
