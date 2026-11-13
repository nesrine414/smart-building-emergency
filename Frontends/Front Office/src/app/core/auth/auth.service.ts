import { Injectable, signal, computed } from '@angular/core';
import { Router } from '@angular/router';
import { User, LoginRequest, LoginResponse } from '../models';

@Injectable({ providedIn: 'root' })
export class AuthService {

  private readonly currentUser = signal<User | null>(null);
  private readonly token = signal<string | null>(null);

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUser() !== null);

  constructor(private readonly router: Router) {
    this.loadFromStorage();
  }

  /**
   * Mock login — will be replaced by real IAM call.
   */
  login(request: LoginRequest): boolean {
    // Mock credentials
    if (request.email === 'user@smartbuilding.com' && request.password === 'password') {
      const mockUser: User = {
        id: 'u-001',
        email: request.email,
        firstName: 'Ines',
        lastName: 'Occupant',
        role: 'OCCUPANT',
        buildingId: 'b-001',
        floorId: 'f-002',
        roomId: 'r-005'
      };
      const mockResponse: LoginResponse = {
        accessToken: 'mock-jwt-token-' + Date.now(),
        refreshToken: 'mock-refresh-token',
        expiresIn: 3600,
        user: mockUser
      };
      this.setSession(mockResponse);
      return true;
    }
    return false;
  }

  logout(): void {
    this.currentUser.set(null);
    this.token.set(null);
    localStorage.removeItem('auth_token');
    localStorage.removeItem('auth_user');
    this.router.navigate(['/login']);
  }

  getToken(): string | null {
    return this.token();
  }

  private setSession(response: LoginResponse): void {
    this.currentUser.set(response.user);
    this.token.set(response.accessToken);
    localStorage.setItem('auth_token', response.accessToken);
    localStorage.setItem('auth_user', JSON.stringify(response.user));
  }

  private loadFromStorage(): void {
    const token = localStorage.getItem('auth_token');
    const userJson = localStorage.getItem('auth_user');
    if (token && userJson) {
      try {
        this.currentUser.set(JSON.parse(userJson));
        this.token.set(token);
      } catch {
        this.logout();
      }
    }
  }
}
