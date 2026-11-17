import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { SwPush } from '@angular/service-worker';
import { Router } from '@angular/router';
import { firstValueFrom, take } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface PushLocation {
  buildingId: string;
  floor: number;
  roomId: string;
}

interface PublicKeyResponse {
  publicKey?: string;
}

@Injectable({ providedIn: 'root' })
export class PushNotificationService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush);
  private readonly router = inject(Router);
  private readonly pushUrl = `${environment.apiUrl}/push`;
  private readonly storageKey = 'smart-building-push-endpoint';

  readonly isSupported = this.swPush.isEnabled;
  readonly isSubscribed = signal(false);
  readonly isBusy = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);

  constructor() {
    if (!this.isSupported) return;

    this.swPush.subscription.subscribe({
      next: subscription => {
        const savedEndpoint =
          typeof localStorage !== 'undefined'
            ? localStorage.getItem(this.storageKey)
            : null;

        this.isSubscribed.set(
          !!subscription && savedEndpoint === subscription.endpoint
        );
      },
      error: error => console.warn('Lecture abonnement Push impossible.', error)
    });

    this.swPush.notificationClicks.subscribe(({ notification }) => {
      const data = notification.data as { url?: unknown } | undefined;

      if (typeof data?.url === 'string' && data.url.startsWith('/')) {
        void this.router.navigateByUrl(data.url);
      }
    });
  }

  async enable(location: PushLocation): Promise<void> {
    this.errorMessage.set(null);
    this.successMessage.set(null);

    if (!this.isSupported) {
      this.errorMessage.set(
        'Les notifications nécessitent le build de production et le Service Worker actif.'
      );
      return;
    }

    if (
      !location.buildingId ||
      !location.roomId ||
      !Number.isInteger(location.floor)
    ) {
      this.errorMessage.set(
        'Sélectionnez un bâtiment, un étage et une salle.'
      );
      return;
    }

    if (
      typeof Notification === 'undefined' ||
      typeof window === 'undefined' ||
      !('PushManager' in window)
    ) {
      this.errorMessage.set(
        'Ce navigateur ne prend pas en charge les notifications Push.'
      );
      return;
    }

    this.isBusy.set(true);

    try {
      let permission = Notification.permission;

      if (permission !== 'granted') {
        permission = await Notification.requestPermission();
      }

      if (permission !== 'granted') {
        throw new Error(
          'Permission refusée. Autorisez les notifications dans les paramètres du navigateur.'
        );
      }

      const keyResponse = await firstValueFrom(
        this.http.get<PublicKeyResponse>(
          `${this.pushUrl}/public-key`
        )
      );

      const publicKey = keyResponse.publicKey?.trim();

      if (!publicKey) {
        throw new Error('La clé publique VAPID est vide côté serveur.');
      }

      let subscription = await firstValueFrom(
        this.swPush.subscription.pipe(take(1))
      );

      if (!subscription) {
        subscription = await this.swPush.requestSubscription({
          serverPublicKey: publicKey
        });
      }

      const json = subscription.toJSON();
      const p256dh = json.keys?.['p256dh'];
      const auth = json.keys?.['auth'];

      if (!subscription.endpoint || !p256dh || !auth) {
        throw new Error('Abonnement navigateur incomplet.');
      }

      await firstValueFrom(
        this.http.post(`${this.pushUrl}/subscriptions`, {
          subscription: {
            endpoint: subscription.endpoint,
            keys: { p256dh, auth }
          },
          buildingId: location.buildingId,
          floor: location.floor,
          roomId: location.roomId
        })
      );

      localStorage.setItem(this.storageKey, subscription.endpoint);
      this.isSubscribed.set(true);
      this.successMessage.set(
        'Notifications activées pour la salle sélectionnée.'
      );
    } catch (error) {
      console.error('Activation Push impossible.', error);
      this.isSubscribed.set(false);

      if (error instanceof HttpErrorResponse) {
        const apiMessage = error.error?.error;
        this.errorMessage.set(
          typeof apiMessage === 'string'
            ? apiMessage
            : `Erreur API HTTP ${error.status}. Vérifiez votre session et le serveur.`
        );
      } else {
        this.errorMessage.set(
          error instanceof Error
            ? error.message
            : 'Erreur pendant l’activation des notifications.'
        );
      }
    } finally {
      this.isBusy.set(false);
    }
  }

  async disable(): Promise<void> {
    this.errorMessage.set(null);
    this.successMessage.set(null);

    if (!this.isSupported) {
      this.errorMessage.set('Service Worker Push non actif.');
      return;
    }

    this.isBusy.set(true);

    try {
      const subscription = await firstValueFrom(
        this.swPush.subscription.pipe(take(1))
      );

      if (subscription) {
        const params = new HttpParams().set(
          'endpoint',
          subscription.endpoint
        );

        // Retirer d'abord l'abonnement serveur.
        await firstValueFrom(
          this.http.delete(`${this.pushUrl}/subscriptions`, { params })
        );

        // Puis désabonner le navigateur.
        await this.swPush.unsubscribe();
      }

      localStorage.removeItem(this.storageKey);
      this.isSubscribed.set(false);
      this.successMessage.set('Notifications désactivées sur ce navigateur.');
    } catch (error) {
      console.error('Désactivation Push impossible.', error);

      this.errorMessage.set(
        error instanceof Error
          ? error.message
          : 'Impossible de désactiver les notifications.'
      );
    } finally {
      this.isBusy.set(false);
    }
  }
}