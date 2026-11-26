
package com.smartbuilding.iam;

import com.smartbuilding.entities.User;
import com.smartbuilding.repositories.UserRepository;
import com.smartbuilding.security.OidcIssuerConfig;
import com.smartbuilding.security.PasswordUtil;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger LOG =
            Logger.getLogger(AuthResource.class.getName());

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_WINDOW_MS = 60_000L;

    private static final int MAX_USERNAME_LENGTH = 128;
    private static final int MAX_PASSWORD_LENGTH = 1024;

    /*
     * Protection contre les tentatives répétées de connexion.
     *
     * Attention : cette limitation est conservée en mémoire.
     * Elle ne remplace pas une limitation centralisée si plusieurs
     * instances de l'application sont déployées.
     */
    private static final ConcurrentHashMap<String, LoginAttempt> LOGIN_ATTEMPTS =
            new ConcurrentHashMap<>();

    @Inject
    private UserRepository userRepository;

    @Inject
    private OidcKeyService keyService;

    /**
     * Les comptes de démonstration sont désactivés par défaut.
     *
     * Pour les activer explicitement dans un environnement de test :
     * ENABLE_DEMO_USERS=true
     *
     * Ne pas activer cette option en production.
     */
    @PostConstruct
    public void initDemoUsers() {

        boolean demoUsersEnabled = Boolean.parseBoolean(
                System.getenv("ENABLE_DEMO_USERS")
        );

        if (!demoUsersEnabled) {
            LOG.info("Création automatique des comptes de démonstration désactivée.");
            return;
        }

        createDemoUser(
                "admin",
                "admin123",
                "ADMIN",
                "Gestionnaire"
        );

        createDemoUser(
                "occupant",
                "occupant123",
                "OCCUPANT",
                "Occupant"
        );
    }

    private void createDemoUser(
            String username,
            String password,
            String role,
            String name
    ) {

        try {
            if (userRepository.findByUsername(username).isPresent()) {
                return;
            }

            User user = new User(
                    UUID.randomUUID().toString(),
                    username,
                    PasswordUtil.hashPassword(password),
                    role,
                    name
            );

            userRepository.save(user);

            // Ne jamais journaliser les identifiants de connexion.
            LOG.info("Compte de démonstration créé : " + username);

        } catch (Exception e) {
            LOG.log(
                    Level.SEVERE,
                    "Impossible de créer un compte de démonstration.",
                    e
            );
        }
    }

    /**
     * Authentification directe temporaire.
     *
     * Cette route est conservée pour assurer la compatibilité
     * avec le Front Office existant pendant sa migration vers OIDC.
     *
     * À supprimer lorsque le Front Office utilisera exclusivement
     * Authorization Code Flow + PKCE.
     */
    @POST
    @Path("/login")
    public Response login(Map<String, String> credentials) {

        // 1. Vérification du corps de la requête.
        if (credentials == null) {
            return error(
                    Response.Status.BAD_REQUEST,
                    "invalid_request",
                    "Corps de la requête manquant."
            );
        }

        String username = credentials.get("username");
        String password = credentials.get("password");

        if (username == null || password == null) {
            return error(
                    Response.Status.BAD_REQUEST,
                    "invalid_request",
                    "username et password sont obligatoires."
            );
        }

        username = username.trim();

        if (username.isEmpty()
                || password.isEmpty()
                || username.length() > MAX_USERNAME_LENGTH
                || password.length() > MAX_PASSWORD_LENGTH) {

            return error(
                    Response.Status.BAD_REQUEST,
                    "invalid_request",
                    "Identifiants invalides."
            );
        }

        // 2. Clé normalisée pour la limitation des tentatives.
        String attemptKey = username.toLowerCase(Locale.ROOT);

        // 3. Vérification de la limitation des tentatives.
        if (isRateLimited(attemptKey)) {
            return error(
                    Response.Status.TOO_MANY_REQUESTS,
                    "too_many_attempts",
                    "Trop de tentatives. Réessayez ultérieurement."
            );
        }

        // 4. Recherche de l'utilisateur et vérification du mot de passe.
        try {

            Optional<User> optUser =
                    userRepository.findByUsername(username);

            if (optUser.isEmpty()) {
                registerFailedAttempt(attemptKey);

                return invalidCredentials();
            }

            User user = optUser.get();

            String passwordHash = user.getPasswordHash();

            if (passwordHash == null
                    || passwordHash.isBlank()
                    || !PasswordUtil.verifyPassword(password, passwordHash)) {

                registerFailedAttempt(attemptKey);

                return invalidCredentials();
            }

            // 5. Vérification des informations nécessaires à la création du token.
            if (user.getId() == null
                    || user.getUsername() == null
                    || user.getRole() == null
                    || user.getName() == null) {

                LOG.severe("Compte utilisateur incomplet.");

                return error(
                        Response.Status.INTERNAL_SERVER_ERROR,
                        "server_error",
                        "Impossible de finaliser l'authentification."
                );
            }

            // 6. Réinitialisation du compteur après authentification réussie.
            LOGIN_ATTEMPTS.remove(attemptKey);

            // 7. Génération du JWT signé en RS256.
            String issuer = OidcIssuerConfig.getIssuer();

            String token = keyService.generateAccessToken(
                    issuer,
                    user.getId(),
                    user.getUsername(),
                    user.getName(),
                    user.getRole()
            );

            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                        "Le service de génération du token a retourné une valeur vide."
                );
            }

            // 8. Durée de validité annoncée : 15 minutes.
            long expiresAt =
                    System.currentTimeMillis() + (15 * 60 * 1000L);

            // 9. Réponse sans informations sensibles.
            return Response.ok(
                    Map.of(
                            "token", token,
                            "tokenType", "Bearer",
                            "role", user.getRole(),
                            "name", user.getName(),
                            "expiresAt", expiresAt
                    )
            )
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .build();

        } catch (Exception e) {

            LOG.log(
                    Level.SEVERE,
                    "Échec du traitement de l'authentification.",
                    e
            );

            return error(
                    Response.Status.INTERNAL_SERVER_ERROR,
                    "server_error",
                    "Une erreur interne est survenue."
            );
        }
    }

    /**
     * Vérifie si l'utilisateur a atteint la limite de tentatives.
     */
    private boolean isRateLimited(String username) {

        LoginAttempt attempt = LOGIN_ATTEMPTS.get(username);

        if (attempt == null) {
            return false;
        }

        long now = System.currentTimeMillis();

        if (now - attempt.windowStart >= LOGIN_WINDOW_MS) {
            LOGIN_ATTEMPTS.remove(username, attempt);
            return false;
        }

        return attempt.failures >= MAX_LOGIN_ATTEMPTS;
    }

    /**
     * Enregistre un échec de connexion.
     */
    private void registerFailedAttempt(String username) {

        long now = System.currentTimeMillis();

        LOGIN_ATTEMPTS.compute(username, (key, current) -> {

            if (current == null
                    || now - current.windowStart >= LOGIN_WINDOW_MS) {

                return new LoginAttempt(now, 1);
            }

            return new LoginAttempt(
                    current.windowStart,
                    current.failures + 1
            );
        });
    }

    /**
     * Réponse générique pour éviter de révéler si un compte existe.
     */
    private Response invalidCredentials() {

        return error(
                Response.Status.UNAUTHORIZED,
                "invalid_credentials",
                "Identifiants invalides."
        );
    }

    /**
     * Construction uniforme des réponses d'erreur.
     */
    private Response error(
            Response.Status status,
            String error,
            String description
    ) {

        return Response.status(status)
                .entity(
                        Map.of(
                                "error", error,
                                "message", description
                        )
                )
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .build();
    }

    /**
     * État des tentatives de connexion.
     */
    private static final class LoginAttempt {

        private final long windowStart;
        private final int failures;

        private LoginAttempt(long windowStart, int failures) {
            this.windowStart = windowStart;
            this.failures = failures;
        }
    }
}
