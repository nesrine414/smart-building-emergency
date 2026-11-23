
export const environment = {
  production: false,
  useMock: false,

  // API et IAM accessibles via le proxy Angular.
  apiUrl: '/api',
  iamUrl: '/iam/api',
  wsUrl: '/api/ws/events',

  // Configuration OpenID Connect du Back Office.
  oidc: {
    clientId: 'sbe-admin',

    issuer: 'https://iam.sbe-team.me/iam/api/oauth2',

    discoveryUrl: '/iam/api/oauth2/.well-known/openid-configuration',

    redirectUri: 'https://admin.sbe-team.me/callback',

    scope: 'openid profile'
  }
};
