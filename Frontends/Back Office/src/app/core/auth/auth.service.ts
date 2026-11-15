import { Injectable, PLATFORM_ID, computed, inject, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface BackOfficeUser {
  username: string;
  name: string;
  role: 'ADMIN';
}

interface LoginResponse {
  token: string;
  role: string;
  name: string;
  expiresAt: number;
}

export interface LoginRequest {
  username: string;
  password: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly platformId = inject(PLATFORM_ID);

  private readonly currentUser = signal<BackOfficeUser | null>(null);
  private readonly token = signal<string | null>(null);
  private readonly forbiddenMessage = signal('');

  readonly user = this.currentUser.asReadonly();
  readonly accessDeniedMessage = this.forbiddenMessage.asReadonly();

  readonly isAuthenticated = computed(
    () => this.currentUser() !== null && this.token() !== null
  );

  private expiryTimer: number | null = null;

  constructor() {
    this.restoreSession();
  }

  login(request: LoginRequest): Observable<BackOfficeUser> {
    return this.http.post<LoginResponse>(
      `${environment.iamUrl}/auth/login`,
      request
    ).pipe(
      map(response => this.createAdminSession(response, request.username))
    );
  }

  getToken(): string | null {
    return this.token();
  }

  isAdmin(): boolean {
    return this.currentUser()?.role === 'ADMIN' && this.token() !== null;
  }

  logout(): void {
    this.clearSession();
    this.router.navigateByUrl('/login');
  }

  expireSession(): void {
    this.clearSession();
    this.router.navigate(['/login'], {
      queryParams: { reason: 'expired' }
    });
  }

  notifyAccessDenied(): void {
    this.forbiddenMessage.set('Réservé aux administrateurs.');
  }

  clearAccessDenied(): void {
    this.forbiddenMessage.set('');
  }

  private createAdminSession(
    response: LoginResponse,
    username: string
  ): BackOfficeUser {
    if (response.role !== 'ADMIN') {
      throw new Error(
        'Accès réservé aux administrateurs. Ce compte occupant ne peut pas ouvrir le Back Office.'
      );
    }

    const expiresAt = Number(response.expiresAt);

    if (
      !response.token ||
      !Number.isFinite(expiresAt) ||
      expiresAt <= Date.now()
    ) {
      throw new Error('Le serveur a retourné une session invalide ou expirée.');
    }

    const user: BackOfficeUser = {
      username,
      name: response.name,
      role: 'ADMIN'
    };

    this.currentUser.set(user);
    this.token.set(response.token);

    if (isPlatformBrowser(this.platformId)) {
      sessionStorage.setItem('admin_auth_token', response.token);
      sessionStorage.setItem('admin_auth_user', JSON.stringify(user));
      sessionStorage.setItem('admin_auth_expires_at', String(expiresAt));
      this.scheduleExpiration(expiresAt);
    }

    return user;
  }

  private restoreSession(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const token = sessionStorage.getItem('admin_auth_token');
    const userJson = sessionStorage.getItem('admin_auth_user');
    const expiresAt = Number(
      sessionStorage.getItem('admin_auth_expires_at')
    );

    if (
      !token ||
      !userJson ||
      !Number.isFinite(expiresAt) ||
      expiresAt <= Date.now()
    ) {
      this.clearSession();
      return;
    }

    try {
      const user = JSON.parse(userJson) as BackOfficeUser;

      if (user.role !== 'ADMIN' || !user.username) {
        this.clearSession();
        return;
      }

      this.currentUser.set(user);
      this.token.set(token);
      this.scheduleExpiration(expiresAt);
    } catch {
      this.clearSession();
    }
  }

  private scheduleExpiration(expiresAt: number): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    if (this.expiryTimer !== null) {
      window.clearTimeout(this.expiryTimer);
    }

    const delay = Math.max(0, expiresAt - Date.now());

    this.expiryTimer = window.setTimeout(() => {
      this.expireSession();
    }, delay);
  }

  private clearSession(): void {
    this.currentUser.set(null);
    this.token.set(null);

    if (isPlatformBrowser(this.platformId)) {
      if (this.expiryTimer !== null) {
        window.clearTimeout(this.expiryTimer);
        this.expiryTimer = null;
      }

      sessionStorage.removeItem('admin_auth_token');
      sessionStorage.removeItem('admin_auth_user');
      sessionStorage.removeItem('admin_auth_expires_at');
    }
  }
}
