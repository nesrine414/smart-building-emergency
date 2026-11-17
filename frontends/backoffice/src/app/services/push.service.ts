
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { SwPush } from '@angular/service-worker';
import { firstValueFrom } from 'rxjs';

import { environment } from '../../environments/environment';
import { OccupantProfileService } from './occupant-profile.service';

interface PushKeyResponse {
  publicKey: string;
}

@Injectable({
  providedIn: 'root'
})
export class PushService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush);
  private readonly profileService = inject(OccupantProfileService);

  private readonly apiUrl = environment.apiUrl;

  async enableNotifications(): Promise<void> {
    if (!this.swPush.isEnabled) {
      throw new Error('Service Worker non activé. Utilisez la version de production.');
    }

    const profile = this.profileService.getProfile();

    if (!profile) {
      throw new Error('Veuillez enregistrer votre profil occupant.');
    }

    if (!('Notification' in globalThis)) {
      throw new Error('Notifications non disponibles dans ce navigateur.');
    }

    // Demander l'autorisation immédiatement après le clic.
    const permission = await Notification.requestPermission();

    if (permission !== 'granted') {
      throw new Error('Autorisation des notifications non accordée.');
    }

    // Récupérer la clé publique VAPID.
    const response = await firstValueFrom(
      this.http.get<PushKeyResponse>(
        `${this.apiUrl}/push/public-key`
      )
    );

    // Créer l'abonnement Push.
    const subscription = await this.swPush.requestSubscription({
      serverPublicKey: response.publicKey
    });

    const floorNumber = Number(profile.floorId.match(/\d+/)?.[0]);

    if (!Number.isInteger(floorNumber) || floorNumber < 0) {
      throw new Error('Identifiant de l’étage invalide.');
    }

    // Envoyer l'abonnement au backend.
    await firstValueFrom(
      this.http.post(
        `${this.apiUrl}/push/subscriptions`,
        {
          subscription: subscription.toJSON(),
          buildingId: profile.buildingId,
          floor: floorNumber,
          roomId: profile.roomId
        }
      )
    );
  }

  async disableNotifications(): Promise<void> {
    if (!this.swPush.isEnabled) {
      return;
    }

    const subscription = await firstValueFrom(
      this.swPush.subscription
    );

    if (!subscription) {
      return;
    }

    await firstValueFrom(
      this.http.delete(
        `${this.apiUrl}/push/subscriptions`,
        {
          params: { endpoint: subscription.endpoint }
        }
      )
    );

    await subscription.unsubscribe();
  }
}
