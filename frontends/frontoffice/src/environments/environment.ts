
export const environment = {
  production: false,
  useMock: false,
  apiUrl: '/api',
  iamUrl: '/iam/api',
  wsUrl: '/api/ws/events',

  oidc: {
    clientId: 'sbe-front',
    issuer: 'https://iam.sbe-team.me/iam/api/oauth2',
    discoveryUrl: '/iam/api/oauth2/.well-known/openid-configuration',
    redirectUri: 'https://www.sbe-team.me/callback',
    scope: 'openid profile'
  }
};
