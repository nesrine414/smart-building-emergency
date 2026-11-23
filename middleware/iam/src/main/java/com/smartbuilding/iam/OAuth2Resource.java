
package com.smartbuilding.iam;

import com.smartbuilding.entities.User;
import com.smartbuilding.repositories.UserRepository;
import com.smartbuilding.security.PasswordUtil;
import com.smartbuilding.security.OidcIssuerConfig;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Path("/oauth2")
@ApplicationScoped
public class OAuth2Resource {

    private static final Logger LOG =
            Logger.getLogger(OAuth2Resource.class.getName());

    private static final Set<String> ALLOWED_CLIENTS =
            Set.of("sbe-front", "sbe-admin");

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_ATTEMPT_WINDOW_MS = 60_000L;
    private static final long AUTH_CODE_TTL_MS = 60_000L;
    private static final long REFRESH_TOKEN_TTL_MS = 8 * 60 * 60 * 1000L;

    private static final String TOKEN_CACHE_CONTROL = "no-store";
    private static final String TOKEN_PRAGMA = "no-cache";

    @Inject
    private UserRepository userRepository;

    @Inject
    private OidcKeyService keyService;

    private final SecureRandom secureRandom = new SecureRandom();

    /*
     * Codes d'autorisation :
     * - durée de vie : 60 secondes ;
     * - utilisation unique ;
     * - suppression atomique lors de l'échange.
     */
    private final Map<String, AuthCodeData> authCodes =
            new ConcurrentHashMap<>();

    /*
     * Refresh tokens :
     * - durée de vie : 8 heures ;
     * - rotation à chaque utilisation ;
     * - utilisation unique lors du renouvellement.
     */
    private final Map<String, RefreshTokenData> refreshTokens =
            new ConcurrentHashMap<>();

    /*
     * Limitation des tentatives de connexion :
     * 5 tentatives maximum par fenêtre de 60 secondes et par identifiant.
     */
    private final Map<String, LoginAttemptData> loginAttempts =
            new ConcurrentHashMap<>();

    private record LoginAttemptData(
            long windowStartedAt,
            int attempts
    ) {}

    // ---------------------------------------------------------------------
    // 1. OPENID CONNECT DISCOVERY
    // ---------------------------------------------------------------------

    @GET
    @Path("/.well-known/openid-configuration")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getOpenIdConfiguration(@Context UriInfo uriInfo) {

        String issuer = OidcIssuerConfig.getIssuer();

        String json = "{"
                + "\"issuer\":" + jsonString(issuer) + ","
                + "\"authorization_endpoint\":"
                + jsonString(issuer + "/authorize") + ","
                + "\"token_endpoint\":"
                + jsonString(issuer + "/token") + ","
                + "\"jwks_uri\":"
                + jsonString(issuer + "/jwks") + ","
                + "\"revocation_endpoint\":"
                + jsonString(issuer + "/revoke") + ","
                + "\"response_types_supported\":[\"code\"],"
                + "\"subject_types_supported\":[\"public\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"],"
                + "\"code_challenge_methods_supported\":[\"S256\"],"
                + "\"grant_types_supported\":["
                + "\"authorization_code\",\"refresh_token\"]"
                + "}";

        return Response.ok(json)
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .build();
    }

    // ---------------------------------------------------------------------
    // 2. JSON WEB KEY SET
    // ---------------------------------------------------------------------

    @GET
    @Path("/jwks")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJwks() {

        return Response.ok(keyService.getJwksJson())
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .build();
    }

    // ---------------------------------------------------------------------
    // 3. AUTHORIZE : AFFICHAGE DU FORMULAIRE DE CONNEXION
    // ---------------------------------------------------------------------

    @GET
    @Path("/authorize")
    @Produces(MediaType.TEXT_HTML)
    public Response authorize(
            @QueryParam("response_type") String responseType,
            @QueryParam("client_id") String clientId,
            @QueryParam("redirect_uri") String redirectUri,
            @QueryParam("scope") String scope,
            @QueryParam("state") String state,
            @QueryParam("nonce") String nonce,
            @QueryParam("code_challenge") String codeChallenge,
            @QueryParam("code_challenge_method") String codeChallengeMethod) {

        // Un client inconnu ne doit jamais recevoir de redirection.
        if (clientId == null || !ALLOWED_CLIENTS.contains(clientId)) {
            return secureHtmlResponse(
                    Response.status(Response.Status.BAD_REQUEST)
            ).entity(
                    "<h2>Erreur OAuth 2.0</h2>"
                    + "<p>client_id inconnu ou invalide.</p>"
            ).build();
        }

        // Vérification stricte de l'URI de redirection.
        if (!isAllowedRedirectUri(clientId, redirectUri)) {
            return secureHtmlResponse(
                    Response.status(Response.Status.BAD_REQUEST)
            ).entity(
                    "<h2>Erreur OAuth 2.0</h2>"
                    + "<p>redirect_uri non autorisée pour ce client.</p>"
            ).build();
        }

        // Le serveur ne prend en charge que le flux Authorization Code.
        if (!"code".equals(responseType)) {
            return redirectWithError(
                    redirectUri,
                    "unsupported_response_type",
                    state
            );
        }

        // PKCE S256 est obligatoire.
        if (!isValidPkceChallenge(
                codeChallenge,
                codeChallengeMethod)) {

            return redirectWithError(
                    redirectUri,
                    "invalid_request",
                    state
            );
        }

        String html = renderLoginPage(
                clientId,
                redirectUri,
                state,
                nonce,
                codeChallenge,
                codeChallengeMethod,
                null
        );

        return secureHtmlResponse(Response.ok(html)).build();
    }

    // ---------------------------------------------------------------------
    // 4. AUTHORIZE : TRAITEMENT DU FORMULAIRE DE CONNEXION
    // ---------------------------------------------------------------------

    @POST
    @Path("/authorize")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response processLogin(
            @FormParam("username") String username,
            @FormParam("password") String password,
            @FormParam("client_id") String clientId,
            @FormParam("redirect_uri") String redirectUri,
            @FormParam("state") String state,
            @FormParam("nonce") String nonce,
            @FormParam("code_challenge") String codeChallenge,
            @FormParam("code_challenge_method") String codeChallengeMethod) {

        // Vérification du client.
        if (clientId == null || !ALLOWED_CLIENTS.contains(clientId)) {
            return secureHtmlResponse(
                    Response.status(Response.Status.BAD_REQUEST)
            ).entity(
                    "<h2>Requête OAuth2 invalide : client inconnu.</h2>"
            ).build();
        }

        // Vérification de l'URI de redirection.
        if (!isAllowedRedirectUri(clientId, redirectUri)) {
            return secureHtmlResponse(
                    Response.status(Response.Status.BAD_REQUEST)
            ).entity(
                    "<h2>Requête OAuth2 invalide : redirection interdite.</h2>"
            ).build();
        }

        // Vérification PKCE.
        if (!isValidPkceChallenge(
                codeChallenge,
                codeChallengeMethod)) {

            return secureHtmlResponse(
                    Response.status(Response.Status.BAD_REQUEST)
            ).entity(
                    "<h2>Requête OAuth2 invalide : challenge PKCE incorrect.</h2>"
            ).build();
        }
        if (state == null || state.isBlank()
        || nonce == null || nonce.isBlank()) {

                return secureHtmlResponse(
                        Response.status(Response.Status.BAD_REQUEST)
                ).entity(
                        "<h2>Requête OAuth2 invalide : state ou nonce manquant.</h2>"
                ).build();
                }

        // Vérification des identifiants.
        if (username == null || username.isBlank()
                || password == null || password.isEmpty()) {

            return secureHtmlResponse(
                    Response.status(Response.Status.BAD_REQUEST)
            ).entity(
                    "<h2>Username and password are required.</h2>"
            ).build();
        }

        String normalizedUsername = username.trim();
        String attemptKey = normalizedUsername.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        // Compteur atomique des tentatives.
        LoginAttemptData attemptData = loginAttempts.compute(
                attemptKey,
                (key, current) -> {

                    if (current == null
                            || now - current.windowStartedAt()
                            >= LOGIN_ATTEMPT_WINDOW_MS) {

                        return new LoginAttemptData(now, 1);
                    }

                    return new LoginAttemptData(
                            current.windowStartedAt(),
                            current.attempts() + 1
                    );
                }
        );

        if (attemptData.attempts() > MAX_LOGIN_ATTEMPTS) {

            long remainingMs = LOGIN_ATTEMPT_WINDOW_MS
                    - (now - attemptData.windowStartedAt());

            long retryAfterSeconds = Math.max(
                    1,
                    (remainingMs + 999) / 1000
            );

            return secureHtmlResponse(
                    Response.status(429)
                            .header(
                                    "Retry-After",
                                    String.valueOf(retryAfterSeconds)
                            )
            ).entity(
                    "<h2>Trop de tentatives</h2>"
                    + "<p>Veuillez réessayer dans "
                    + retryAfterSeconds
                    + " seconde(s).</p>"
            ).build();
        }

        Optional<User> optUser =
                userRepository.findByUsername(normalizedUsername);

        if (optUser.isEmpty()
                || !PasswordUtil.verifyPassword(
                        password,
                        optUser.isPresent()
                                ? optUser.get().getPasswordHash()
                                : null)) {

            String errorHtml = renderLoginPage(
                    clientId,
                    redirectUri,
                    state,
                    nonce,
                    codeChallenge,
                    codeChallengeMethod,
                    "Invalid username or password."
            );

            return secureHtmlResponse(
                    Response.status(Response.Status.UNAUTHORIZED)
            ).entity(errorHtml).build();
        }

        User user = optUser.get();

        // Le client administrateur est réservé aux comptes ADMIN.
        if ("sbe-admin".equals(clientId)
                && !"ADMIN".equalsIgnoreCase(user.getRole())) {

            return redirectWithError(
                    redirectUri,
                    "access_denied",
                    state
            );
        }

        // Connexion réussie : réinitialisation du compteur.
        loginAttempts.remove(attemptKey);

        // Génération d'un code d'autorisation aléatoire de 256 bits.
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        String code = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        long expiresAt = System.currentTimeMillis()
                + AUTH_CODE_TTL_MS;

        AuthCodeData codeData = new AuthCodeData(
                code,
                clientId,
                redirectUri,
                codeChallenge,
                user.getId(),
                user.getUsername(),
                user.getName(),
                user.getRole(),
                nonce,
                expiresAt
        );

        authCodes.put(code, codeData);

        // Construction de la redirection avec encodage des paramètres.
        String target = redirectUri
                + (redirectUri.contains("?") ? "&" : "?")
                + "code=" + URLEncoder.encode(
                        code,
                        StandardCharsets.UTF_8
                )
                + (state != null
                        ? "&state=" + URLEncoder.encode(
                                state,
                                StandardCharsets.UTF_8
                        )
                        : "");

        return Response.seeOther(URI.create(target))
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    // ---------------------------------------------------------------------
    // 5. TOKEN : AUTHORIZATION CODE ET REFRESH TOKEN
    // ---------------------------------------------------------------------

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response exchangeToken(
            @FormParam("grant_type") String grantType,
            @FormParam("code") String code,
            @FormParam("redirect_uri") String redirectUri,
            @FormParam("client_id") String clientId,
            @FormParam("code_verifier") String codeVerifier,
            @FormParam("refresh_token") String refreshToken,
            @Context UriInfo uriInfo) {

        String issuer = OidcIssuerConfig.getIssuer();

        if (clientId == null || !ALLOWED_CLIENTS.contains(clientId)) {
            return tokenError(
                    "invalid_client",
                    "client_id inconnu ou invalide."
            );
        }

        // -------------------------------------------------------------
        // A. ÉCHANGE AUTHORIZATION CODE
        // -------------------------------------------------------------

        if ("authorization_code".equalsIgnoreCase(grantType)) {

            if (code == null || code.isBlank()) {
                return tokenError(
                        "invalid_grant",
                        "Code d'autorisation manquant."
                );
            }

            /*
             * On consulte d'abord le code sans le supprimer.
             * Cela évite qu'un client non propriétaire puisse invalider
             * un code en tentant de l'échanger.
             */
            AuthCodeData codeData = authCodes.get(code);

            if (codeData == null) {
                return tokenError(
                        "invalid_grant",
                        "Code inconnu ou déjà utilisé."
                );
            }

            if (System.currentTimeMillis() >= codeData.expiresAt) {

                authCodes.remove(code, codeData);

                return tokenError(
                        "invalid_grant",
                        "Code d'autorisation expiré."
                );
            }

            if (!codeData.clientId.equals(clientId)
                    || !codeData.redirectUri.equals(redirectUri)) {

                return tokenError(
                        "invalid_grant",
                        "client_id ou redirect_uri ne correspond pas."
                );
            }

            // Vérification cryptographique PKCE.
            if (!verifyPkce(codeVerifier, codeData.codeChallenge)) {
                return tokenError(
                        "invalid_grant",
                        "code_verifier invalide (échec PKCE)."
                );
            }

            /*
             * Suppression atomique :
             * une seule requête concurrente peut consommer le code.
             */
            if (!authCodes.remove(code, codeData)) {
                return tokenError(
                        "invalid_grant",
                        "Code déjà utilisé."
                );
            }

            try {
                String accessToken = keyService.generateAccessToken(
                        issuer,
                        codeData.userId,
                        codeData.username,
                        codeData.name,
                        codeData.role
                );

                String idToken = keyService.generateIdToken(
                        issuer,
                        codeData.userId,
                        codeData.username,
                        codeData.name,
                        clientId,
                        codeData.nonce
                );

                String newRefreshToken = generateOpaqueToken();

                refreshTokens.put(
                        newRefreshToken,
                        new RefreshTokenData(
                                newRefreshToken,
                                codeData.userId,
                                codeData.username,
                                codeData.name,
                                codeData.role,
                                clientId,
                                System.currentTimeMillis()
                                        + REFRESH_TOKEN_TTL_MS
                        )
                );

                String json = "{"
                        + "\"access_token\":"
                        + jsonString(accessToken) + ","
                        + "\"id_token\":"
                        + jsonString(idToken) + ","
                        + "\"token_type\":\"Bearer\","
                        + "\"expires_in\":900,"
                        + "\"refresh_token\":"
                        + jsonString(newRefreshToken)
                        + "}";

                return tokenResponse(json);

            } catch (Exception e) {

                LOG.severe(
                        "Erreur lors de la génération des jetons OIDC : "
                        + e.getClass().getSimpleName()
                );

                return tokenError(
                        "server_error",
                        "Impossible de générer les jetons."
                );
            }
        }

        // -------------------------------------------------------------
        // B. RENOUVELLEMENT AVEC REFRESH TOKEN
        // -------------------------------------------------------------

        if ("refresh_token".equalsIgnoreCase(grantType)) {

            if (refreshToken == null || refreshToken.isBlank()) {
                return tokenError(
                        "invalid_grant",
                        "Refresh token manquant."
                );
            }

            /*
             * Lecture avant suppression :
             * un autre client ne peut pas invalider le token.
             */
            RefreshTokenData rtData =
                    refreshTokens.get(refreshToken);

            if (rtData == null) {
                return tokenError(
                        "invalid_grant",
                        "Refresh token invalide ou déjà utilisé."
                );
            }

            if (!rtData.clientId.equals(clientId)) {
                return tokenError(
                        "invalid_grant",
                        "Refresh token invalide pour ce client."
                );
            }

            if (System.currentTimeMillis() >= rtData.expiresAt) {

                refreshTokens.remove(refreshToken, rtData);

                return tokenError(
                        "invalid_grant",
                        "Refresh token expiré."
                );
            }

            /*
             * Rotation atomique :
             * une seule requête peut consommer l'ancien refresh token.
             */
            if (!refreshTokens.remove(refreshToken, rtData)) {
                return tokenError(
                        "invalid_grant",
                        "Refresh token déjà utilisé."
                );
            }

            try {
                String newAccessToken =
                        keyService.generateAccessToken(
                                issuer,
                                rtData.userId,
                                rtData.username,
                                rtData.name,
                                rtData.role
                        );

                String rotatedRefreshToken = generateOpaqueToken();

                refreshTokens.put(
                        rotatedRefreshToken,
                        new RefreshTokenData(
                                rotatedRefreshToken,
                                rtData.userId,
                                rtData.username,
                                rtData.name,
                                rtData.role,
                                clientId,
                                System.currentTimeMillis()
                                        + REFRESH_TOKEN_TTL_MS
                        )
                );

                String json = "{"
                        + "\"access_token\":"
                        + jsonString(newAccessToken) + ","
                        + "\"token_type\":\"Bearer\","
                        + "\"expires_in\":900,"
                        + "\"refresh_token\":"
                        + jsonString(rotatedRefreshToken)
                        + "}";

                return tokenResponse(json);

            } catch (Exception e) {

                LOG.severe(
                        "Erreur lors du renouvellement du jeton OIDC : "
                        + e.getClass().getSimpleName()
                );

                return tokenError(
                        "server_error",
                        "Impossible de renouveler les jetons."
                );
            }
        }

        return tokenError(
                "unsupported_grant_type",
                "grant_type non supporté."
        );
    }

    // ---------------------------------------------------------------------
    // 6. REVOKE : RÉVOCATION DU REFRESH TOKEN
    // ---------------------------------------------------------------------

    @POST
    @Path("/revoke")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response revokeToken(
            @FormParam("token") String token,
            @FormParam("client_id") String clientId) {

        /*
         * RFC 7009 : réponse 200 même si le token est inconnu.
         * Un client ne peut révoquer que ses propres refresh tokens.
         */
        if (token != null
                && !token.isBlank()
                && clientId != null
                && ALLOWED_CLIENTS.contains(clientId)) {

            RefreshTokenData tokenData = refreshTokens.get(token);

            if (tokenData != null
                    && tokenData.clientId.equals(clientId)) {

                refreshTokens.remove(token, tokenData);
            }
        }

        return Response.ok()
                .header("Cache-Control", TOKEN_CACHE_CONTROL)
                .header("Pragma", TOKEN_PRAGMA)
                .build();
    }

    // ---------------------------------------------------------------------
    // 7. UTILITAIRES : GÉNÉRATION ET VALIDATION DES TOKENS
    // ---------------------------------------------------------------------

    private String generateOpaqueToken() {

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    /*
     * RFC 7636 :
     * code_verifier doit contenir entre 43 et 128 caractères
     * parmi ALPHA / DIGIT / "-" / "." / "_" / "~".
     */
    private boolean isValidCodeVerifier(String codeVerifier) {

        if (codeVerifier == null) {
            return false;
        }

        return codeVerifier.matches(
                "^[A-Za-z0-9._~-]{43,128}$"
        );
    }

    /*
     * Vérification PKCE S256 :
     * BASE64URL(SHA256(code_verifier)) == code_challenge.
     */
    private boolean verifyPkce(
            String codeVerifier,
            String codeChallenge) {

        if (!isValidCodeVerifier(codeVerifier)
                || !isValidPkceChallenge(codeChallenge, "S256")) {

            return false;
        }

        try {
            MessageDigest sha256 =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = sha256.digest(
                    codeVerifier.getBytes(StandardCharsets.US_ASCII)
            );

            String calculated = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(hash);

            return MessageDigest.isEqual(
                    calculated.getBytes(StandardCharsets.US_ASCII),
                    codeChallenge.getBytes(StandardCharsets.US_ASCII)
            );

        } catch (Exception e) {
            LOG.warning("Erreur pendant la vérification PKCE.");
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // 8. VALIDATION DES REDIRECT URI
    // ---------------------------------------------------------------------


        private boolean isAllowedRedirectUri(
                String clientId,
                String redirectUri) {

        if (clientId == null || redirectUri == null) {
                return false;
        }

        // Front Office : www.sbe-team.me
        if ("sbe-front".equals(clientId)) {
                return "https://www.sbe-team.me/callback"
                        .equals(redirectUri);
        }

        // Back Office : admin.sbe-team.me
        if ("sbe-admin".equals(clientId)) {
                return "https://admin.sbe-team.me/callback"
                        .equals(redirectUri);
        }

        return false;
        }

    // ---------------------------------------------------------------------
    // 9. VALIDATION DU CHALLENGE PKCE
    // ---------------------------------------------------------------------

    private boolean isValidPkceChallenge(
            String codeChallenge,
            String method) {

        if (!"S256".equals(method) || codeChallenge == null) {
            return false;
        }

        // SHA-256 encodé en Base64URL sans padding = 43 caractères.
        return codeChallenge.matches("^[A-Za-z0-9_-]{43}$");
    }

    // ---------------------------------------------------------------------
    // 10. REDIRECTION AVEC ERREUR OAUTH2
    // ---------------------------------------------------------------------

    private Response redirectWithError(
            String redirectUri,
            String error,
            String state) {

        String target = redirectUri
                + (redirectUri.contains("?") ? "&" : "?")
                + "error=" + URLEncoder.encode(
                        error,
                        StandardCharsets.UTF_8
                )
                + (state != null
                        ? "&state=" + URLEncoder.encode(
                                state,
                                StandardCharsets.UTF_8
                        )
                        : "");

        return Response.seeOther(URI.create(target))
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    // ---------------------------------------------------------------------
    // 11. RÉPONSES JSON DE TOKEN
    // ---------------------------------------------------------------------

    private Response tokenResponse(String json) {

        return Response.ok(json)
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", TOKEN_CACHE_CONTROL)
                .header("Pragma", TOKEN_PRAGMA)
                .build();
    }

    private Response tokenError(
            String error,
            String description) {

        String json = "{"
                + "\"error\":" + jsonString(error) + ","
                + "\"error_description\":"
                + jsonString(description)
                + "}";

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(json)
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", TOKEN_CACHE_CONTROL)
                .header("Pragma", TOKEN_PRAGMA)
                .build();
    }

    /*
     * Échappement JSON pour éviter de construire des chaînes JSON
     * incorrectes ou injectables.
     */
    private static String jsonString(String value) {

        if (value == null) {
            return "null";
        }

        StringBuilder result = new StringBuilder("\"");

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");

                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }

        result.append("\"");
        return result.toString();
    }

    // ---------------------------------------------------------------------
    // 12. EN-TÊTES DE SÉCURITÉ HTML
    // ---------------------------------------------------------------------

    private Response.ResponseBuilder secureHtmlResponse(
            Response.ResponseBuilder response) {

        return response
                .type("text/html; charset=UTF-8")
                .header(
                        "Cache-Control",
                        "no-store, no-cache, must-revalidate"
                )
                .header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header(
                        "Content-Security-Policy",
                        "default-src 'none'; "
                        + "style-src 'unsafe-inline'; "
                        + "base-uri 'none'; "
                        + "frame-ancestors 'none'"
                );
    }

    // ---------------------------------------------------------------------
    // 13. ÉCHAPPEMENT HTML
    // ---------------------------------------------------------------------

    private static String escapeHtml(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    // ---------------------------------------------------------------------
    // 14. GÉNÉRATION DE LA PAGE DE CONNEXION
    // ---------------------------------------------------------------------

    private String renderLoginPage(
            String clientId,
            String redirectUri,
            String state,
            String nonce,
            String codeChallenge,
            String codeChallengeMethod,
            String error) {

        String safeClientId = escapeHtml(clientId);
        String safeRedirectUri = escapeHtml(redirectUri);
        String safeState = escapeHtml(state);
        String safeNonce = escapeHtml(nonce);
        String safeCodeChallenge = escapeHtml(codeChallenge);
        String safeCodeChallengeMethod =
                escapeHtml(codeChallengeMethod);
        String safeError = escapeHtml(error);

        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\">\n"
                + "  <meta name=\"viewport\" "
                + "content=\"width=device-width, initial-scale=1.0\">\n"
                + "  <title>Smart Building IAM - Sign In</title>\n"
                + "  <style>\n"
                + "    body { font-family: -apple-system, "
                + "BlinkMacSystemFont, sans-serif; "
                + "background: #0f172a; color: #f8fafc; "
                + "display: flex; justify-content: center; "
                + "align-items: center; min-height: 100vh; margin: 0; }\n"
                + "    .card { background: #1e293b; padding: 2rem; "
                + "border-radius: 12px; width: 340px; "
                + "max-width: calc(100% - 48px); "
                + "box-shadow: 0 10px 25px rgba(0,0,0,0.5); }\n"
                + "    h2 { text-align: center; margin-top: 0; "
                + "color: #38bdf8; }\n"
                + "    input { width: 100%; box-sizing: border-box; "
                + "padding: 10px; margin: 8px 0 16px 0; "
                + "border-radius: 6px; border: 1px solid #475569; "
                + "background: #0f172a; color: #fff; }\n"
                + "    button { width: 100%; padding: 12px; "
                + "background: #0284c7; color: #fff; border: none; "
                + "border-radius: 6px; font-weight: bold; cursor: pointer; }\n"
                + "    button:hover { background: #0369a1; }\n"
                + "    .error { background: #ef444433; "
                + "border: 1px solid #ef4444; color: #fca5a5; "
                + "padding: 8px; border-radius: 6px; "
                + "margin-bottom: 12px; font-size: 14px; "
                + "text-align: center; }\n"
                + "  </style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"card\">\n"
                + "  <h2>Smart Building IAM</h2>\n"
                + "  <p style=\"font-size:13px; color:#94a3b8; "
                + "text-align:center;\">"
                + "Secure OAuth 2.0 PKCE Authentication</p>\n"
                + (error != null && !error.isBlank()
                        ? "  <div class=\"error\" role=\"alert\">"
                        + safeError + "</div>\n"
                        : "")
                + "  <form method=\"POST\" action=\"authorize\" "
                + "autocomplete=\"on\">\n"
                + "    <input type=\"hidden\" name=\"client_id\" "
                + "value=\"" + safeClientId + "\">\n"
                + "    <input type=\"hidden\" name=\"redirect_uri\" "
                + "value=\"" + safeRedirectUri + "\">\n"
                + "    <input type=\"hidden\" name=\"state\" "
                + "value=\"" + safeState + "\">\n"
                + "    <input type=\"hidden\" name=\"nonce\" "
                + "value=\"" + safeNonce + "\">\n"
                + "    <input type=\"hidden\" name=\"code_challenge\" "
                + "value=\"" + safeCodeChallenge + "\">\n"
                + "    <input type=\"hidden\" "
                + "name=\"code_challenge_method\" "
                + "value=\"" + safeCodeChallengeMethod + "\">\n"
                + "    <label for=\"username\">Username</label>\n"
                + "    <input id=\"username\" type=\"text\" "
                + "name=\"username\" autocomplete=\"username\" "
                + "required autofocus>\n"
                + "    <label for=\"password\">Password</label>\n"
                + "    <input id=\"password\" type=\"password\" "
                + "name=\"password\" "
                + "autocomplete=\"current-password\" required>\n"
                + "    <button type=\"submit\">Sign In</button>\n"
                + "  </form>\n"
                + "</div>\n"
                + "</body>\n"
                + "</html>";
    }

    // ---------------------------------------------------------------------
    // 15. DONNÉES DES CODES D'AUTORISATION
    // ---------------------------------------------------------------------

    private static class AuthCodeData {

        final String code;
        final String clientId;
        final String redirectUri;
        final String codeChallenge;
        final String userId;
        final String username;
        final String name;
        final String role;
        final String nonce;
        final long expiresAt;

        AuthCodeData(
                String code,
                String clientId,
                String redirectUri,
                String codeChallenge,
                String userId,
                String username,
                String name,
                String role,
                String nonce,
                long expiresAt) {

            this.code = code;
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.codeChallenge = codeChallenge;
            this.userId = userId;
            this.username = username;
            this.name = name;
            this.role = role;
            this.nonce = nonce;
            this.expiresAt = expiresAt;
        }
    }

    // ---------------------------------------------------------------------
    // 16. DONNÉES DES REFRESH TOKENS
    // ---------------------------------------------------------------------

    private static class RefreshTokenData {

        final String token;
        final String userId;
        final String username;
        final String name;
        final String role;
        final String clientId;
        final long expiresAt;

        RefreshTokenData(
                String token,
                String userId,
                String username,
                String name,
                String role,
                String clientId,
                long expiresAt) {

            this.token = token;
            this.userId = userId;
            this.username = username;
            this.name = name;
            this.role = role;
            this.clientId = clientId;
            this.expiresAt = expiresAt;
        }
    }
}
