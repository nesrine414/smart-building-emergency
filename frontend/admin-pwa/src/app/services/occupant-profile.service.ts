
import { Injectable } from '@angular/core';
import { OccupantProfile } from '../models/occupant-profile.model';

@Injectable({
  providedIn: 'root'
})
export class OccupantProfileService {
  private readonly storageKey = 'smart-building-occupant-profile';

  getProfile(): OccupantProfile | null {
    if (typeof localStorage === 'undefined') {
      return null;
    }

    const savedProfile = localStorage.getItem(this.storageKey);

    if (!savedProfile) {
      return null;
    }

    try {
      return JSON.parse(savedProfile) as OccupantProfile;
    } catch {
      return null;
    }
  }

  saveProfile(profile: OccupantProfile): void {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(this.storageKey, JSON.stringify(profile));
    }
  }
}
