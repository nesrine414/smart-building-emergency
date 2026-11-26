import {
  Injectable,
  PLATFORM_ID,
  computed,
  inject,
  signal
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface BackOfficeUser {
  username: string;
  name: string;
  role: 'ADMIN';
}

interface OidcDiscovery {
  issuer: string;
  authorization_endpoint: string;
  token_endpoint: string;
  jwks_uri: string;
  revocation_endpoint?: string;
}

interface TokenResponse {
  access_token: string;
  id_token?: string;
  token_type: string;
  expires_in: number;
  refresh_token?: string;
}

interface PkceTransaction {
  state: string;
  nonce: string;
  codeVerifier: string;
  createdAt: number;
}

interface JwtHeader {
  alg?: string;
  kid?: string;
}

interface JwtClaims {
  iss?: string;
  sub?: string;
  aud?: string | string[];
  exp?: number;
  iat?: number;
  nbf?: number;
  nonce?: string;
  username?: string;
  name?: string;
  role?: string;
}

interface JwkWithMetadata extends JsonWebKey {
  kid?: string;
  use?: string;
  alg?: string;
}

interface JwksResponse {
  keys: JwkWithMetadata[];
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly platformId = inject(PLATFORM_ID);

  private readonly currentUser = signal<BackOfficeUser | null>(null);
  private readonly accessToken = signal<string | null>(null);
  private readonly forbiddenMessage = signal('');

  readonly user = this.currentUser.asReadonly();
  readonly accessDeniedMessage = this.forbiddenMessage.asReadonly();

  readonly isAuthenticated = computed(
    () => this.currentUser() !== null && this.accessToken() !== null
  );

  private refreshToken: string | null = null;
  private refreshTimer: number | null = null;

  private readonly transactionKey = 'sbe_oidc_transaction';

  private get isBrowser(): boolean {
    return isPlatformBrowser(this.platformId);
  }

  // ----------------------------------------------------------
  // START AUTHORIZATION CODE + PKCE
  // ----------------------------------------------------------

  async beginLogin(): Promise<void> {
    if (!this.isBrowser) {
      throw new Error('Authentification disponible uniquement dans le navigateur.');
    }

    if (!window.crypto?.subtle) {
      throw new Error('Web Crypto est indisponible. Utilisez HTTPS ou localhost.');
    }

    const discovery = await this.getDiscovery();

    const state = this.randomBase64Url(32);
    const nonce = this.randomBase64Url(32);
    const codeVerifier = this.randomBase64Url(64);

    const challengeBytes = await window.crypto.subtle.digest(
      'SHA-256',
      new TextEncoder().encode(codeVerifier)
    );

    const codeChallenge = this.bytesToBase64Url(
      new Uint8Array(challengeBytes)
    );

    const transaction: PkceTransaction = {
      state,
      nonce,
      codeVerifier,
      createdAt: Date.now()
    };

    sessionStorage.setItem(
      this.transactionKey,
      JSON.stringify(transaction)
    );

    const authorizationUrl = new URL(discovery.authorization_endpoint);

    authorizationUrl.searchParams.set('response_type', 'code');
    authorizationUrl.searchParams.set('client_id', environment.oidc.clientId);
    authorizationUrl.searchParams.set('redirect_uri', environment.oidc.redirectUri);
    authorizationUrl.searchParams.set('scope', environment.oidc.scope);
    authorizationUrl.searchParams.set('state', state);
    authorizationUrl.searchParams.set('nonce', nonce);
    authorizationUrl.searchParams.set('code_challenge', codeChallenge);
    authorizationUrl.searchParams.set('code_challenge_method', 'S256');

    window.location.assign(authorizationUrl.toString());
  }

  // ----------------------------------------------------------
  // CALLBACK : VALIDATE STATE, EXCHANGE CODE, VALIDATE TOKENS
  // ----------------------------------------------------------

  async completeLogin(code: string, returnedState: string): Promise<void> {
    if (!this.isBrowser) {
      throw new Error('Callback OAuth indisponible hors navigateur.');
    }

    const storedTransaction = sessionStorage.getItem(this.transactionKey);

    // Remove the transaction immediately to prevent replay.
    sessionStorage.removeItem(this.transactionKey);

    if (!storedTransaction) {
      throw new Error('Transaction PKCE absente ou deja utilisee.');
    }

    let transaction: PkceTransaction;

    try {
      transaction = JSON.parse(storedTransaction) as PkceTransaction;
    } catch {
      throw new Error('Transaction PKCE invalide.');
    }

    if (
      !transaction.state ||
      !transaction.nonce ||
      !transaction.codeVerifier ||
      transaction.state !== returnedState
    ) {
      throw new Error('Validation state OAuth echouee.');
    }

    if (
      !Number.isFinite(transaction.createdAt) ||
      Date.now() - transaction.createdAt > 5 * 60 * 1000 ||
      transaction.createdAt > Date.now()
    ) {
      throw new Error('Transaction PKCE expiree.');
    }

    const discovery = await this.getDiscovery();

    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: environment.oidc.clientId,
      code,
      redirect_uri: environment.oidc.redirectUri,
      code_verifier: transaction.codeVerifier
    });

    const response = await firstValueFrom(
      this.http.post<TokenResponse>(
        discovery.token_endpoint,
        body.toString(),
        {
          headers: new HttpHeaders({
            'Content-Type': 'application/x-www-form-urlencoded'
          })
        }
      )
    );

    if (
      !response.access_token ||
      !response.id_token ||
      response.token_type?.toLowerCase() !== 'bearer' ||
      !Number.isFinite(response.expires_in) ||
      response.expires_in <= 0 ||
      !response.refresh_token
    ) {
      throw new Error('Reponse OAuth incomplete ou invalide.');
    }

    // Verify ID token signature, issuer, audience, expiration and nonce.
    const idClaims = await this.verifyJwt(
      response.id_token,
      discovery,
      environment.oidc.clientId
    );

    if (idClaims.nonce !== transaction.nonce) {
      throw new Error('Validation nonce OpenID Connect echouee.');
    }

    // The IAM ID token does not contain the role.
    // Verify the access token before trusting its ADMIN claim.
    const accessClaims = await this.verifyJwt(
      response.access_token,
      discovery,
      'sbe-api'
    );

    if (accessClaims.role !== 'ADMIN') {
      throw new Error('Acces refuse : ce compte ne possede pas le role ADMIN.');
    }

    const username = accessClaims.username;
    const name = accessClaims.name;

    if (!username || !name || !accessClaims.sub) {
      throw new Error('Informations administrateur absentes du token.');
    }

    const user: BackOfficeUser = {
      username,
      name,
      role: 'ADMIN'
    };

    this.currentUser.set(user);
    this.accessToken.set(response.access_token);
    this.refreshToken = response.refresh_token;

    this.scheduleRefresh(response.expires_in);
  }

  // ----------------------------------------------------------
  // TOKEN ACCESS
  // ----------------------------------------------------------

  getToken(): string | null {
    return this.accessToken();
  }

  isAdmin(): boolean {
    return (
      this.currentUser()?.role === 'ADMIN' &&
      this.accessToken() !== null
    );
  }

  // ----------------------------------------------------------
  // REFRESH TOKEN ROTATION
  // ----------------------------------------------------------

  private scheduleRefresh(expiresInSeconds: number): void {
    if (!this.isBrowser) {
      return;
    }

    if (this.refreshTimer !== null) {
      window.clearTimeout(this.refreshTimer);
    }

    // Refresh one minute before access-token expiration.
    const delay = Math.max(5000, (expiresInSeconds - 60) * 1000);

    this.refreshTimer = window.setTimeout(() => {
      void this.refreshSession();
    }, delay);
  }

  private async refreshSession(): Promise<void> {
    if (!this.refreshToken || !this.isBrowser) {
      this.expireSession();
      return;
    }

    try {
      const discovery = await this.getDiscovery();

      const body = new URLSearchParams({
        grant_type: 'refresh_token',
        client_id: environment.oidc.clientId,
        refresh_token: this.refreshToken
      });

      const response = await firstValueFrom(
        this.http.post<TokenResponse>(
          discovery.token_endpoint,
          body.toString(),
          {
            headers: new HttpHeaders({
              'Content-Type': 'application/x-www-form-urlencoded'
            })
          }
        )
      );

      if (
        !response.access_token ||
        !response.refresh_token ||
        response.token_type?.toLowerCase() !== 'bearer' ||
        !Number.isFinite(response.expires_in) ||
        response.expires_in <= 0
      ) {
        throw new Error('Reponse refresh invalide.');
      }

      const claims = await this.verifyJwt(
        response.access_token,
        discovery,
        'sbe-api'
      );

      if (
        claims.role !== 'ADMIN' ||
        !claims.username ||
        !claims.name ||
        !claims.sub
      ) {
        throw new Error('Le token renouvele ne possede pas le role ADMIN.');
      }

      this.currentUser.set({
        username: claims.username,
        name: claims.name,
        role: 'ADMIN'
      });

      this.accessToken.set(response.access_token);

      // IAM rotates the refresh token.
      this.refreshToken = response.refresh_token;

      this.scheduleRefresh(response.expires_in);
    } catch {
      this.expireSession();
    }
  }

  // ----------------------------------------------------------
  // LOGOUT AND SESSION MANAGEMENT
  // ----------------------------------------------------------

  logout(): void {
    this.clearSession();

    if (this.isBrowser) {
      void this.router.navigateByUrl('/login');
    }
  }

  expireSession(): void {
    this.clearSession();

    if (this.isBrowser) {
      void this.router.navigate(['/login'], {
        queryParams: { reason: 'expired' }
      });
    }
  }

  notifyAccessDenied(): void {
    this.forbiddenMessage.set('Acces reserve aux administrateurs.');
  }

  clearAccessDenied(): void {
    this.forbiddenMessage.set('');
  }

  private clearSession(): void {
    this.currentUser.set(null);
    this.accessToken.set(null);
    this.refreshToken = null;

    if (this.isBrowser) {
      if (this.refreshTimer !== null) {
        window.clearTimeout(this.refreshTimer);
        this.refreshTimer = null;
      }

      sessionStorage.removeItem(this.transactionKey);
    }
  }

  // ----------------------------------------------------------
  // OIDC DISCOVERY
  // ----------------------------------------------------------

  private async getDiscovery(): Promise<OidcDiscovery> {
    const discovery = await firstValueFrom(
      this.http.get<OidcDiscovery>(environment.oidc.discoveryUrl)
    );

    if (
      !discovery.issuer ||
      !discovery.authorization_endpoint ||
      !discovery.token_endpoint ||
      !discovery.jwks_uri
    ) {
      throw new Error('Configuration OpenID Connect incomplete.');
    }

    return discovery;
  }

  // ----------------------------------------------------------
  // JWT RS256 SIGNATURE AND CLAIM VALIDATION
  // ----------------------------------------------------------

  private async verifyJwt(
    token: string,
    discovery: OidcDiscovery,
    expectedAudience: string
  ): Promise<JwtClaims> {
    if (!this.isBrowser || !window.crypto?.subtle) {
      throw new Error('Web Crypto indisponible.');
    }

    const parts = token.split('.');

    if (parts.length !== 3) {
      throw new Error('Format JWT invalide.');
    }

    const header = this.decodeJwtPart<JwtHeader>(parts[0]);

    if (header.alg !== 'RS256' || !header.kid) {
      throw new Error('Algorithme ou cle JWT non autorise.');
    }

    const claims = this.decodeJwtPart<JwtClaims>(parts[1]);

    const jwks = await firstValueFrom(
      this.http.get<JwksResponse>(discovery.jwks_uri)
    );

    const jwk = jwks.keys?.find(
      key =>
        key.kid === header.kid &&
        key.use !== 'enc' &&
        (!key.alg || key.alg === 'RS256')
    );

    if (!jwk) {
      throw new Error('Cle publique JWT introuvable dans JWKS.');
    }

    const publicKey = await window.crypto.subtle.importKey(
      'jwk',
      jwk,
      {
        name: 'RSASSA-PKCS1-v1_5',
        hash: 'SHA-256'
      },
      false,
      ['verify']
    );

    const signingInput = new TextEncoder().encode(
      `${parts[0]}.${parts[1]}`
    );

    const signature = this.base64UrlToBytes(parts[2]);

    // Copy typed arrays into standard ArrayBuffers for WebCrypto.
    const signatureBuffer = new ArrayBuffer(signature.byteLength);
    new Uint8Array(signatureBuffer).set(signature);

    const signingInputBuffer = new ArrayBuffer(signingInput.byteLength);
    new Uint8Array(signingInputBuffer).set(signingInput);

    const signatureValid = await window.crypto.subtle.verify(
      'RSASSA-PKCS1-v1_5',
      publicKey,
      signatureBuffer,
      signingInputBuffer
    );

    if (!signatureValid) {
      throw new Error('Signature JWT RS256 invalide.');
    }

    const now = Math.floor(Date.now() / 1000);

    const audienceValid = Array.isArray(claims.aud)
      ? claims.aud.includes(expectedAudience)
      : claims.aud === expectedAudience;

    if (
      claims.iss !== discovery.issuer ||
      !audienceValid ||
      !Number.isFinite(claims.exp) ||
      (claims.exp as number) <= now ||
      (claims.nbf !== undefined && claims.nbf > now)
    ) {
      throw new Error('Claims JWT issuer, audience ou expiration invalides.');
    }

    return claims;
  }

  // ----------------------------------------------------------
  // CRYPTOGRAPHIC HELPERS
  // ----------------------------------------------------------

  private randomBase64Url(byteLength: number): string {
    const bytes = new Uint8Array(byteLength);
    window.crypto.getRandomValues(bytes);
    return this.bytesToBase64Url(bytes);
  }

  private bytesToBase64Url(bytes: Uint8Array): string {
    let binary = '';

    for (const byte of bytes) {
      binary += String.fromCharCode(byte);
    }

    return btoa(binary)
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/g, '');
  }

  private base64UrlToBytes(value: string): Uint8Array {
    const base64 = value
      .replace(/-/g, '+')
      .replace(/_/g, '/');

    const padded = base64.padEnd(
      base64.length + ((4 - (base64.length % 4)) % 4),
      '='
    );

    const binary = atob(padded);

    return Uint8Array.from(
      binary,
      character => character.charCodeAt(0)
    );
  }

  private decodeJwtPart<T>(part: string): T {
    const bytes = this.base64UrlToBytes(part);
    const decoded = new TextDecoder().decode(bytes);
    return JSON.parse(decoded) as T;
  }
}