
import { Injectable, inject, signal, computed, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, map } from 'rxjs';
import { User, LoginRequest, LoginResponse } from '../models';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly platformId = inject(PLATFORM_ID);

  private readonly currentUser = signal<User | null>(null);
  private readonly token = signal<string | null>(null);
  private readonly deniedMessage = signal('');

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(
    () => this.currentUser() !== null && this.token() !== null
  );
  readonly accessDeniedMessage = this.deniedMessage.asReadonly();

  private expiryTimer: number | null = null;

  constructor() {
    this.loadFromStorage();
  }

  login(request: LoginRequest): Observable<User> {
    return this.http.post<LoginResponse>(
      `${environment.iamUrl}/auth/login`,
      request
    ).pipe(
      map(response => {
        const expiresAt = Number(response.expiresAt);

        if (!response.token || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
          throw new Error('Session invalide ou expirée.');
        }

        const user: User = {
          id: request.username,
          username: request.username,
          email: request.username,
          firstName: response.name,
          lastName: '',
          role: response.role
        };

        this.setSession(response, user);
        return user;
      })
    );
  }

  getToken(): string | null {
    return this.token();
  }

  notifyAccessDenied(): void {
    this.deniedMessage.set('Réservé aux administrateurs.');
  }

  clearAccessDenied(): void {
    this.deniedMessage.set('');
  }

  logout(): void {
    this.clearSession();
    this.router.navigate(['/login']);
  }

  private setSession(response: LoginResponse, user: User): void {
    this.currentUser.set(user);
    this.token.set(response.token);

    if (isPlatformBrowser(this.platformId)) {
      const expiresAt = Number(response.expiresAt);

      sessionStorage.setItem('auth_token', response.token);
      sessionStorage.setItem('auth_user', JSON.stringify(user));
      sessionStorage.setItem('auth_expires_at', String(expiresAt));

      this.scheduleExpiration(expiresAt);
    }
  }

  private loadFromStorage(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    const token = sessionStorage.getItem('auth_token');
    const userJson = sessionStorage.getItem('auth_user');
    const expiresAt = Number(sessionStorage.getItem('auth_expires_at'));

    if (!token || !userJson || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      this.clearSession();
      return;
    }

    try {
      const user = JSON.parse(userJson) as User;
      this.currentUser.set(user);
      this.token.set(token);
      this.scheduleExpiration(expiresAt);
    } catch {
      this.clearSession();
    }
  }

  private scheduleExpiration(expiresAt: number): void {
    if (!isPlatformBrowser(this.platformId)) return;

    if (this.expiryTimer !== null) {
      window.clearTimeout(this.expiryTimer);
    }

    this.expiryTimer = window.setTimeout(
      () => this.logout(),
      Math.max(0, expiresAt - Date.now())
    );
  }

  private clearSession(): void {
    this.currentUser.set(null);
    this.token.set(null);

    if (isPlatformBrowser(this.platformId)) {
      if (this.expiryTimer !== null) {
        window.clearTimeout(this.expiryTimer);
        this.expiryTimer = null;
      }

      sessionStorage.removeItem('auth_token');
      sessionStorage.removeItem('auth_user');
      sessionStorage.removeItem('auth_expires_at');
    }
  }
}
