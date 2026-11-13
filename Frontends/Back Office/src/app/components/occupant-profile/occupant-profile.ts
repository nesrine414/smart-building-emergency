
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { OccupantProfile } from '../../models/occupant-profile.model';
import { OccupantProfileService } from '../../services/occupant-profile.service';
import { PushService } from '../../services/push.service';

@Component({
  selector: 'app-occupant-profile',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './occupant-profile.html',
  styleUrl: './occupant-profile.scss'
})
export class OccupantProfileComponent implements OnInit {

  profile: OccupantProfile = {
    id: 'occupant-1',
    name: '',
    buildingId: '',
    floorId: '',
    roomId: ''
  };

  message = '';
  pushMessage = '';
  pushLoading = false;
  pushEnabled = false;

  constructor(
    private occupantProfileService: OccupantProfileService,
    private pushService: PushService
  ) {}

  ngOnInit(): void {
    const savedProfile = this.occupantProfileService.getProfile();

    if (savedProfile) {
      this.profile = savedProfile;
    }
  }

  saveProfile(): void {
    if (
      !this.profile.name.trim() ||
      !this.profile.buildingId.trim() ||
      !this.profile.floorId.trim() ||
      !this.profile.roomId.trim()
    ) {
      this.message = 'Veuillez remplir tous les champs.';
      return;
    }

    this.occupantProfileService.saveProfile(this.profile);
    this.message = 'Profil enregistré avec succès.';
  }

  async enablePushNotifications(): Promise<void> {
    this.pushLoading = true;
    this.pushMessage = '';

    try {
      await this.pushService.enableNotifications();
      this.pushEnabled = true;
      this.pushMessage = 'Notifications activées avec succès !';
    } catch (error) {
      console.error('Erreur notifications Push :', error);
      this.pushMessage =
        error instanceof Error
          ? error.message
          : 'Impossible d’activer les notifications.';
    } finally {
      this.pushLoading = false;
    }
  }
}
