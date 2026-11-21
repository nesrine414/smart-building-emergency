package com.smartbuilding.iam;

import com.smartbuilding.entities.User;
import com.smartbuilding.repositories.UserRepository;
import com.smartbuilding.security.PasswordUtil;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Path("/oauth2")
@ApplicationScoped
public class OAuth2Resource {

    private static final Logger LOG = Logger.getLogger(OAuth2Resource.class.getName());

    // Clients autorisés par le contrat
    private static final Set<String> ALLOWED_CLIENTS = Set.of("sbe-front", "sbe-admin");

    @Inject
    private UserRepository userRepository;

    @Inject
    private OidcKeyService keyService;

    private final SecureRandom secureRandom = new SecureRandom();

    // Stockage mémoire des codes d'autorisation (validité 60 s, usage unique)
    private final Map<String, AuthCodeData> authCodes = new ConcurrentHashMap<>();

    // Stockage mémoire des refresh tokens (validité 8h, remplacé à chaque usage)
    private final Map<String, RefreshTokenData> refreshTokens = new ConcurrentHashMap<>();

    // Suivi anti-brute force (5 échecs max par minute -> 429)
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // 1. ROUTE DE DÉCOUVERTE OPENID CONNECT (/.well-known/openid-configuration)
    // -------------------------------------------------------------------------
    @GET
    @Path("/.well-known/openid-configuration")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getOpenIdConfiguration(@Context UriInfo uriInfo) {
        String issuer = getIssuer(uriInfo);

        String json = "{\n"
                + "  \"issuer\": \"" + issuer + "\",\n"
                + "  \"authorization_endpoint\": \"" + issuer + "/authorize\",\n"
                + "  \"token_endpoint\": \"" + issuer + "/token\",\n"
                + "  \"jwks_uri\": \"" + issuer + "/jwks\",\n"
                + "  \"revocation_endpoint\": \"" + issuer + "/revoke\",\n"
                + "  \"response_types_supported\": [\"code\"],\n"
                + "  \"subject_types_supported\": [\"public\"],\n"
                + "  \"id_token_signing_alg_values_supported\": [\"RS256\"],\n"
                + "  \"code_challenge_methods_supported\": [\"S256\"],\n"
                + "  \"grant_types_supported\": [\"authorization_code\", \"refresh_token\"]\n"
                + "}";

        return Response.ok(json).build();
    }

    // -------------------------------------------------------------------------
    // 2. ROUTE DU CATALOGUE DES CLÉS PUBLIQUES (/jwks)
    // -------------------------------------------------------------------------
    @GET
    @Path("/jwks")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJwks() {
        return Response.ok(keyService.getJwksJson()).build();
    }

    // -------------------------------------------------------------------------
    // 3. ROUTE AUTHORIZE (Affichage du formulaire de login ou erreurs)
    // -------------------------------------------------------------------------
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

        // Règle RFC 6749 : si client_id ou redirect_uri est invalide, afficher page d'erreur sans redirection
        if (clientId == null || !ALLOWED_CLIENTS.contains(clientId)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("<h2>Erreur OAuth 2.0</h2><p>client_id inconnu ou invalide.</p>").build();
        }

        if (redirectUri == null || !isAllowedRedirectUri(clientId, redirectUri)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("<h2>Erreur OAuth 2.0</h2><p>redirect_uri non autorisée pour ce client.</p>").build();
        }

        // Pour les autres erreurs : redirection avec error=...
        if (!"code".equals(responseType)) {
            return redirectWithError(redirectUri, "unsupported_response_type", state);
        }

        if (codeChallenge == null || !"S256".equals(codeChallengeMethod)) {
            return redirectWithError(redirectUri, "invalid_request", state);
        }

        // Afficher la page HTML de connexion de l'IAM
        String html = renderLoginPage(clientId, redirectUri, state, nonce, codeChallenge, codeChallengeMethod, null);
        return Response.ok(html).build();
    }

    // Traitement du formulaire de connexion de l'IAM
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

        // Vérification de rate-limiting (max 5 échecs par compte)
        int attempts = failedAttempts.getOrDefault(username != null ? username : "anon", 0);
        if (attempts >= 5) {
            return Response.status(429)
                    .entity("<h2>Trop de tentatives</h2><p>Compte temporairement bloqué (1 minute).</p>").build();
        }

        Optional<User> optUser = (username != null) ? userRepository.findByUsername(username) : Optional.empty();

        if (optUser.isEmpty() || !PasswordUtil.verifyPassword(password, optUser.get().getPasswordHash())) {
            failedAttempts.put(username != null ? username : "anon", attempts + 1);
            String errorHtml = renderLoginPage(clientId, redirectUri, state, nonce, codeChallenge, codeChallengeMethod, "Identifiant ou mot de passe incorrect.");
            return Response.status(Response.Status.UNAUTHORIZED).entity(errorHtml).build();
        }

        // Réinitialiser les échecs après succès
        failedAttempts.remove(username);
        User user = optUser.get();

        // Règle clé du plan (Démo 5) : sbe-admin n'obtient un code QUE pour un compte ADMIN !
        if ("sbe-admin".equals(clientId) && !"ADMIN".equalsIgnoreCase(user.getRole())) {
            return redirectWithError(redirectUri, "access_denied", state);
        }

        // Générer le code d'autorisation (32 octets aléatoires en base64url)
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        // Enregistrer le code pour 60 secondes (usage unique)
        AuthCodeData codeData = new AuthCodeData(
                code, clientId, redirectUri, codeChallenge,
                user.getId(), user.getUsername(), user.getName(), user.getRole(),
                nonce, System.currentTimeMillis() + 60_000L
        );
        authCodes.put(code, codeData);

        // Redirection 302 vers redirect_uri?code=...&state=...
        String target = redirectUri + (redirectUri.contains("?") ? "&" : "?")
                + "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + (state != null ? "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) : "");

        return Response.seeOther(URI.create(target)).build();
    }

    // -------------------------------------------------------------------------
    // 4. ROUTE TOKEN (/token)
    // -------------------------------------------------------------------------
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

        String issuer = getIssuer(uriInfo);

        // A. Échange du Code d'autorisation (PKCE)
        if ("authorization_code".equalsIgnoreCase(grantType)) {
            if (code == null || !authCodes.containsKey(code)) {
                return tokenError("invalid_grant", "Code inconnu ou deja utilise.");
            }

            AuthCodeData codeData = authCodes.remove(code); // Supprimé immédiatement (usage unique !)

            if (System.currentTimeMillis() > codeData.expiresAt) {
                return tokenError("invalid_grant", "Code expire (>60s).");
            }

            if (!codeData.clientId.equals(clientId) || !codeData.redirectUri.equals(redirectUri)) {
                return tokenError("invalid_grant", "client_id ou redirect_uri ne correspond pas.");
            }

            // Vérification cryptographique PKCE : BASE64URL(SHA256(code_verifier)) == code_challenge
            if (!verifyPkce(codeVerifier, codeData.codeChallenge)) {
                return tokenError("invalid_grant", "code_verifier invalide (echec PKCE).");
            }

            // Génération des jetons signés RS256
            String accessToken = keyService.generateAccessToken(issuer, codeData.userId, codeData.username, codeData.name, codeData.role);
            String idToken = keyService.generateIdToken(issuer, codeData.userId, codeData.username, codeData.name, clientId, codeData.nonce);

            // Génération du Refresh Token opaque (8h)
            byte[] rtBytes = new byte[32];
            secureRandom.nextBytes(rtBytes);
            String newRefreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(rtBytes);

            refreshTokens.put(newRefreshToken, new RefreshTokenData(
                    newRefreshToken, codeData.userId, codeData.username, codeData.name,
                    codeData.role, clientId, System.currentTimeMillis() + (8 * 3600 * 1000L)
            ));

            String json = "{\n"
                    + "  \"access_token\": \"" + accessToken + "\",\n"
                    + "  \"id_token\": \"" + idToken + "\",\n"
                    + "  \"token_type\": \"Bearer\",\n"
                    + "  \"expires_in\": 900,\n"
                    + "  \"refresh_token\": \"" + newRefreshToken + "\"\n"
                    + "}";

            return Response.ok(json)
                    .header("Cache-Control", "no-store")
                    .header("Pragma", "no-cache")
                    .build();
        }

        // B. Renouvellement via Refresh Token
        if ("refresh_token".equalsIgnoreCase(grantType)) {
            if (refreshToken == null || !refreshTokens.containsKey(refreshToken)) {
                return tokenError("invalid_grant", "Refresh token invalide ou revoque.");
            }

            RefreshTokenData rtData = refreshTokens.remove(refreshToken); // Rotation immédiate

            if (System.currentTimeMillis() > rtData.expiresAt) {
                return tokenError("invalid_grant", "Refresh token expire.");
            }

            if (!rtData.clientId.equals(clientId)) {
                return tokenError("invalid_grant", "client_id non correspondant.");
            }

            String newAccessToken = keyService.generateAccessToken(issuer, rtData.userId, rtData.username, rtData.name, rtData.role);

            // Nouveau refresh token pour rotation
            byte[] rtBytes = new byte[32];
            secureRandom.nextBytes(rtBytes);
            String rotatedRefreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(rtBytes);

            refreshTokens.put(rotatedRefreshToken, new RefreshTokenData(
                    rotatedRefreshToken, rtData.userId, rtData.username, rtData.name,
                    rtData.role, clientId, System.currentTimeMillis() + (8 * 3600 * 1000L)
            ));

            String json = "{\n"
                    + "  \"access_token\": \"" + newAccessToken + "\",\n"
                    + "  \"token_type\": \"Bearer\",\n"
                    + "  \"expires_in\": 900,\n"
                    + "  \"refresh_token\": \"" + rotatedRefreshToken + "\"\n"
                    + "}";

            return Response.ok(json)
                    .header("Cache-Control", "no-store")
                    .build();
        }

        return tokenError("unsupported_grant_type", "grant_type non supporte.");
    }

    // -------------------------------------------------------------------------
    // 5. ROUTE REVOKE (/revoke)
    // -------------------------------------------------------------------------
    @POST
    @Path("/revoke")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response revokeToken(
            @FormParam("token") String token,
            @FormParam("client_id") String clientId) {

        if (token != null) {
            refreshTokens.remove(token);
        }
        return Response.ok().build();
    }

    // -------------------------------------------------------------------------
    // UTILITAIRES PRIVÉS
    // -------------------------------------------------------------------------
    private boolean verifyPkce(String codeVerifier, String codeChallenge) {
        if (codeVerifier == null || codeChallenge == null) {
            return false;
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String calculated = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return calculated.equals(codeChallenge);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAllowedRedirectUri(String clientId, String redirectUri) {
        if ("sbe-front".equals(clientId)) {
            return redirectUri.contains("/callback") && (
                    redirectUri.startsWith("https://www.") ||
                    redirectUri.startsWith("http://localhost:4200") ||
                    redirectUri.startsWith("https://localhost:4200")
            );
        }
        if ("sbe-admin".equals(clientId)) {
            return redirectUri.contains("/callback") && (
                    redirectUri.startsWith("https://admin.") ||
                    redirectUri.startsWith("http://localhost:4201") ||
                    redirectUri.startsWith("https://localhost:4201")
            );
        }
        return false;
    }

    private Response redirectWithError(String redirectUri, String error, String state) {
        String target = redirectUri + (redirectUri.contains("?") ? "&" : "?")
                + "error=" + URLEncoder.encode(error, StandardCharsets.UTF_8)
                + (state != null ? "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) : "");
        return Response.seeOther(URI.create(target)).build();
    }

    private Response tokenError(String error, String description) {
        String json = "{\"error\":\"" + error + "\",\"error_description\":\"" + description + "\"}";
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(json)
                .header("Cache-Control", "no-store")
                .build();
    }

    private String getIssuer(UriInfo uriInfo) {
        // En production : https://iam.<domaine>/iam/api/oauth2
        String publicDomain = System.getenv("PUBLIC_DOMAIN");
        if (publicDomain != null && !publicDomain.isBlank()) {
            return "https://iam." + publicDomain + "/iam/api/oauth2";
        }
        return uriInfo.getBaseUriBuilder().path("oauth2").build().toString();
    }

    private String renderLoginPage(String clientId, String redirectUri, String state, String nonce,
                                   String codeChallenge, String codeChallengeMethod, String error) {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"fr\">\n"
                + "<head><meta charset=\"UTF-8\"><title>Smart Building - IAM Connexion</title>\n"
                + "<style>\n"
                + "body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #0f172a; color: #f8fafc; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }\n"
                + ".card { background: #1e293b; padding: 2rem; border-radius: 12px; width: 340px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }\n"
                + "h2 { text-align: center; margin-top: 0; color: #38bdf8; }\n"
                + "input { width: 100%; box-sizing: border-box; padding: 10px; margin: 8px 0 16px 0; border-radius: 6px; border: 1px solid #475569; background: #0f172a; color: #fff; }\n"
                + "button { width: 100%; padding: 12px; background: #0284c7; color: #fff; border: none; border-radius: 6px; font-weight: bold; cursor: pointer; }\n"
                + "button:hover { background: #0369a1; }\n"
                + ".error { background: #ef444433; border: 1px solid #ef4444; color: #fca5a5; padding: 8px; border-radius: 6px; margin-bottom: 12px; font-size: 14px; text-align: center; }\n"
                + ".hint { font-size: 12px; color: #94a3b8; text-align: center; margin-top: 16px; }\n"
                + "</style></head>\n"
                + "<body>\n"
                + "<div class=\"card\">\n"
                + "  <h2>Smart Building IAM</h2>\n"
                + "  <p style=\"font-size:13px; color:#94a3b8; text-align:center;\">Connexion sécurisée OAuth 2.0 PKCE</p>\n"
                + (error != null ? "  <div class=\"error\">" + error + "</div>\n" : "")
                + "  <form method=\"POST\" action=\"authorize\">\n"
                + "    <input type=\"hidden\" name=\"client_id\" value=\"" + clientId + "\"/>\n"
                + "    <input type=\"hidden\" name=\"redirect_uri\" value=\"" + redirectUri + "\"/>\n"
                + "    <input type=\"hidden\" name=\"state\" value=\"" + (state != null ? state : "") + "\"/>\n"
                + "    <input type=\"hidden\" name=\"nonce\" value=\"" + (nonce != null ? nonce : "") + "\"/>\n"
                + "    <input type=\"hidden\" name=\"code_challenge\" value=\"" + codeChallenge + "\"/>\n"
                + "    <input type=\"hidden\" name=\"code_challenge_method\" value=\"" + codeChallengeMethod + "\"/>\n"
                + "    <label>Identifiant</label>\n"
                + "    <input type=\"text\" name=\"username\" required autofocus/>\n"
                + "    <label>Mot de passe</label>\n"
                + "    <input type=\"password\" name=\"password\" required/>\n"
                + "    <button type=\"submit\">Se connecter</button>\n"
                + "  </form>\n"
                + "  <div class=\"hint\">Démo : occupant / occupant123 | admin / admin123</div>\n"
                + "</div>\n"
                + "</body></html>";
    }

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

        AuthCodeData(String code, String clientId, String redirectUri, String codeChallenge,
                     String userId, String username, String name, String role, String nonce, long expiresAt) {
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

    private static class RefreshTokenData {
        final String token;
        final String userId;
        final String username;
        final String name;
        final String role;
        final String clientId;
        final long expiresAt;

        RefreshTokenData(String token, String userId, String username, String name, String role, String clientId, long expiresAt) {
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