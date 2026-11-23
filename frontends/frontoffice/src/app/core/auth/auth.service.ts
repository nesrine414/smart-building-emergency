
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

import { User } from '../models';
import { environment } from '../../../environments/environment';

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

  private readonly currentUser = signal<User | null>(null);
  private readonly accessToken = signal<string | null>(null);
  private readonly deniedMessage = signal('');

  readonly user = this.currentUser.asReadonly();
  readonly accessDeniedMessage = this.deniedMessage.asReadonly();

  readonly isAuthenticated = computed(
    () => this.currentUser() !== null && this.accessToken() !== null
  );

  private refreshToken: string | null = null;
  private refreshTimer: number | null = null;

  private readonly transactionKey = 'sbe_front_oidc_transaction';

  private get isBrowser(): boolean {
    return isPlatformBrowser(this.platformId);
  }

  // ==========================================================
  // AUTHORIZATION CODE + PKCE
  // ==========================================================


  async beginLogin(): Promise<void> {
    if (!this.isBrowser) {
      throw new Error('Authentification indisponible hors navigateur.');
    }

    if (!window.crypto?.subtle) {
      throw new Error('Web Crypto indisponible. Utilisez HTTPS.');
    }

    // Le callback doit revenir sur la même origine que celle
    // qui a créé la transaction PKCE.
    const redirectUrl = new URL(environment.oidc.redirectUri);

    if (redirectUrl.origin !== window.location.origin) {
      throw new Error(
        `Origine redirect_uri incorrecte : ${redirectUrl.origin}. ` +
        `Origine actuelle : ${window.location.origin}`
      );
    }

    const discovery = await this.getDiscovery();

    const state = this.randomBase64Url(32);
    const nonce = this.randomBase64Url(32);
    const codeVerifier = this.randomBase64Url(64);

    const challengeDigest = await window.crypto.subtle.digest(
      'SHA-256',
      new TextEncoder().encode(codeVerifier)
    );

    const codeChallenge = this.bytesToBase64Url(
      new Uint8Array(challengeDigest)
    );

    const transaction: PkceTransaction = {
      state,
      nonce,
      codeVerifier,
      createdAt: Date.now()
    };

    try {
      // Une nouvelle tentative remplace toute ancienne transaction.
      window.sessionStorage.removeItem(this.transactionKey);

      window.sessionStorage.setItem(
        this.transactionKey,
        JSON.stringify(transaction)
      );

      // Vérification immédiate de la persistance.
      const savedTransaction =
        window.sessionStorage.getItem(this.transactionKey);

      if (!savedTransaction) {
        throw new Error(
          'Impossible de sauvegarder la transaction PKCE dans sessionStorage.'
        );
      }

      const parsed = JSON.parse(savedTransaction) as PkceTransaction;

      if (
        parsed.state !== state ||
        parsed.codeVerifier !== codeVerifier
      ) {
        throw new Error(
          'La vérification de la transaction PKCE enregistrée a échoué.'
        );
      }

      console.info('[OIDC] Transaction PKCE enregistrée.', {
        origin: window.location.origin,
        redirectOrigin: redirectUrl.origin,
        storageAvailable: true
      });

    } catch (error) {
      console.error('[OIDC] Erreur stockage PKCE :', error);
      throw new Error(
        'Impossible de préparer la transaction de connexion OAuth.'
      );
    }

    const authorizationUrl = new URL(
      discovery.authorization_endpoint
    );

    authorizationUrl.searchParams.set('response_type', 'code');
    authorizationUrl.searchParams.set(
      'client_id',
      environment.oidc.clientId
    );
    authorizationUrl.searchParams.set(
      'redirect_uri',
      environment.oidc.redirectUri
    );
    authorizationUrl.searchParams.set(
      'scope',
      environment.oidc.scope
    );
    authorizationUrl.searchParams.set('state', state);
    authorizationUrl.searchParams.set('nonce', nonce);
    authorizationUrl.searchParams.set(
      'code_challenge',
      codeChallenge
    );
    authorizationUrl.searchParams.set(
      'code_challenge_method',
      'S256'
    );
    console.info('[PKCE DIAGNOSTIC - AVANT REDIRECTION]', {
      origin: window.location.origin,
      href: window.location.href,
      redirectUri: environment.oidc.redirectUri,
      transactionExists:
        window.sessionStorage.getItem(this.transactionKey) !== null,
      transactionKey: this.transactionKey
    });
    // Redirection dans le même onglet pour conserver sessionStorage.
    window.location.assign(authorizationUrl.toString());
  }


  // ==========================================================
  // CALLBACK AND TOKEN EXCHANGE
  // ==========================================================


  async completeLogin(
    code: string,
    returnedState: string
  ): Promise<void> {
    if (!this.isBrowser) {
      throw new Error('Callback OAuth indisponible hors navigateur.');
    }

    if (!code || !returnedState) {
      throw new Error('Code ou state OAuth manquant.');
    }
    console.info('[PKCE DIAGNOSTIC - RETOUR CALLBACK]', {
      origin: window.location.origin,
      href: window.location.href,
      redirectUri: environment.oidc.redirectUri,
      transactionExists:
        window.sessionStorage.getItem(this.transactionKey) !== null,
      transactionKey: this.transactionKey
    });
    const storedTransaction = window.sessionStorage.getItem(
      this.transactionKey
    );

    if (!storedTransaction) {
      console.error('[OIDC] Transaction PKCE introuvable.', {
        currentOrigin: window.location.origin,
        redirectOrigin: new URL(environment.oidc.redirectUri).origin,
        storageAvailable: true
      });

      throw new Error(
        'Transaction PKCE absente. La connexion doit être recommencée dans le même onglet.'
      );
    }

    let transaction: PkceTransaction;

    try {
      transaction = JSON.parse(
        storedTransaction
      ) as PkceTransaction;
    } catch {
      window.sessionStorage.removeItem(this.transactionKey);
      throw new Error('Transaction PKCE illisible.');
    }

    // Vérifier les données sans les supprimer prématurément.
    if (
      !transaction.state ||
      !transaction.nonce ||
      !transaction.codeVerifier ||
      !Number.isFinite(transaction.createdAt)
    ) {
      window.sessionStorage.removeItem(this.transactionKey);
      throw new Error('Transaction PKCE incomplète ou invalide.');
    }

    if (transaction.state !== returnedState) {
      window.sessionStorage.removeItem(this.transactionKey);
      throw new Error('Validation state OAuth échouée.');
    }

    const transactionAge = Date.now() - transaction.createdAt;

    if (
      transactionAge < 0 ||
      transactionAge > 5 * 60 * 1000
    ) {
      window.sessionStorage.removeItem(this.transactionKey);
      throw new Error('Transaction PKCE expirée.');
    }

    // La transaction est valide : on la consomme une seule fois.
    window.sessionStorage.removeItem(this.transactionKey);

    console.info('[OIDC] Transaction PKCE validée.', {
      origin: window.location.origin,
      ageSeconds: Math.floor(transactionAge / 1000)
    });

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
      !response.refresh_token ||
      response.token_type?.toLowerCase() !== 'bearer' ||
      !Number.isFinite(response.expires_in) ||
      response.expires_in <= 0
    ) {
      throw new Error('Réponse OAuth incomplète ou invalide.');
    }

    const idClaims = await this.verifyJwt(
      response.id_token,
      discovery,
      environment.oidc.clientId
    );

    if (idClaims.nonce !== transaction.nonce) {
      throw new Error('Validation nonce OpenID Connect échouée.');
    }

    const accessClaims = await this.verifyJwt(
      response.access_token,
      discovery,
      'sbe-api'
    );

    if (idClaims.sub !== accessClaims.sub) {
      throw new Error(
        'Les sujets des tokens OIDC ne correspondent pas.'
      );
    }

    if (
      accessClaims.role !== 'OCCUPANT' ||
      !accessClaims.username ||
      !accessClaims.name ||
      !accessClaims.sub
    ) {
      throw new Error(
        'Accès refusé : cette interface est réservée aux occupants.'
      );
    }

    const user: User = {
      id: accessClaims.sub,
      username: accessClaims.username,
      email: accessClaims.username,
      firstName: accessClaims.name,
      lastName: '',
      role: 'OCCUPANT'
    };

    this.currentUser.set(user);
    this.accessToken.set(response.access_token);
    this.refreshToken = response.refresh_token;

    this.scheduleRefresh(response.expires_in);
  }


  // ==========================================================
  // ACCESS TOKEN
  // ==========================================================

  getToken(): string | null {
    return this.accessToken();
  }

  // ==========================================================
  // REFRESH TOKEN ROTATION
  // ==========================================================

  private scheduleRefresh(expiresInSeconds: number): void {
    if (!this.isBrowser) {
      return;
    }

    if (this.refreshTimer !== null) {
      window.clearTimeout(this.refreshTimer);
    }

    // Renouveler 60 secondes avant l'expiration.
    const delay = Math.max(
      5000,
      (expiresInSeconds - 60) * 1000
    );

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
        throw new Error('Réponse de renouvellement invalide.');
      }

      const claims = await this.verifyJwt(
        response.access_token,
        discovery,
        'sbe-api'
      );

      if (
        claims.role !== 'OCCUPANT' ||
        !claims.username ||
        !claims.name ||
        !claims.sub
      ) {
        throw new Error('Le token renouvelé ne possède pas le rôle OCCUPANT.');
      }

      const user: User = {
        id: claims.sub,
        username: claims.username,
        email: claims.username,
        firstName: claims.name,
        lastName: '',
        role: 'OCCUPANT'
      };

      this.currentUser.set(user);
      this.accessToken.set(response.access_token);

      // IAM effectue une rotation du refresh token.
      this.refreshToken = response.refresh_token;

      this.scheduleRefresh(response.expires_in);
    } catch {
      this.expireSession();
    }
  }

  // ==========================================================
  // LOGOUT AND SESSION
  // ==========================================================

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
    this.deniedMessage.set('Accès réservé aux occupants autorisés.');
  }

  clearAccessDenied(): void {
    this.deniedMessage.set('');
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

  // ==========================================================
  // OIDC DISCOVERY
  // ==========================================================

  private async getDiscovery(): Promise<OidcDiscovery> {
    const discovery = await firstValueFrom(
      this.http.get<OidcDiscovery>(
        environment.oidc.discoveryUrl
      )
    );

    if (
      !discovery.issuer ||
      !discovery.authorization_endpoint ||
      !discovery.token_endpoint ||
      !discovery.jwks_uri
    ) {
      throw new Error('Configuration OpenID Connect incomplète.');
    }

    if (discovery.issuer !== environment.oidc.issuer) {
      throw new Error('Issuer IAM inattendu.');
    }

    // Les endpoints IAM doivent utiliser HTTPS.
    for (const endpoint of [
      discovery.authorization_endpoint,
      discovery.token_endpoint,
      discovery.jwks_uri
    ]) {
      if (new URL(endpoint).protocol !== 'https:') {
        throw new Error('Un endpoint IAM ne possède pas HTTPS.');
      }
    }

    return discovery;
  }

  // ==========================================================
  // JWT RS256 VALIDATION
  // ==========================================================

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
      throw new Error('Algorithme ou identifiant de clé JWT invalide.');
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
      throw new Error('Clé publique JWT introuvable dans JWKS.');
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

    // Utiliser des ArrayBuffer standards pour WebCrypto.
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
      throw new Error(
        'Issuer, audience, expiration ou nbf du JWT invalide.'
      );
    }

    return claims;
  }

  // ==========================================================
  // CRYPTOGRAPHIC HELPERS
  // ==========================================================

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
