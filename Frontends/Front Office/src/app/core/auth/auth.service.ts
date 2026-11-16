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

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(
    () => this.currentUser() !== null && this.token() !== null
  );

  constructor() {
    this.loadFromStorage();
  }

  login(request: LoginRequest): Observable<User> {
    return this.http.post<LoginResponse>(
      `${environment.iamUrl}/auth/login`,
      request
    ).pipe(
      map(response => {
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

  logout(): void {
    this.currentUser.set(null);
    this.token.set(null);

    if (isPlatformBrowser(this.platformId)) {
      localStorage.removeItem('auth_token');
      localStorage.removeItem('auth_user');
      localStorage.removeItem('auth_expires_at');
    }

    this.router.navigate(['/login']);
  }

  getToken(): string | null {
    return this.token();
  }

  private setSession(response: LoginResponse, user: User): void {
    this.currentUser.set(user);
    this.token.set(response.token);

    if (isPlatformBrowser(this.platformId)) {
      localStorage.setItem('auth_token', response.token);
      localStorage.setItem('auth_user', JSON.stringify(user));
      localStorage.setItem('auth_expires_at', String(response.expiresAt));
    }
  }

  private loadFromStorage(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const token = localStorage.getItem('auth_token');
    const userJson = localStorage.getItem('auth_user');
    const expiresAt = Number(localStorage.getItem('auth_expires_at'));

    if (!token || !userJson || !expiresAt || Date.now() >= expiresAt) {
      localStorage.removeItem('auth_token');
      localStorage.removeItem('auth_user');
      localStorage.removeItem('auth_expires_at');
      return;
    }

    try {
      this.currentUser.set(JSON.parse(userJson) as User);
      this.token.set(token);
    } catch {
      localStorage.removeItem('auth_token');
      localStorage.removeItem('auth_user');
      localStorage.removeItem('auth_expires_at');
    }
  }
}