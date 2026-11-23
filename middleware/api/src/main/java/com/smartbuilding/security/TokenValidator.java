package com.smartbuilding.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class TokenValidator {

    private static final Logger LOG = Logger.getLogger(TokenValidator.class.getName());

    private ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    @PostConstruct
    public void init() {
        try {
            // URL du JWKS (priorité à la variable d'environnement, sinon local WildFly)
            String jwksUrlStr = System.getenv("OIDC_JWKS_URL");
            if (jwksUrlStr == null || jwksUrlStr.isBlank()) {
                jwksUrlStr = "http://localhost:8081/iam/api/oauth2/jwks";
            }

            URL jwksUrl = new URL(jwksUrlStr);
            JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(jwksUrl);
            JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);

            this.jwtProcessor = new DefaultJWTProcessor<>();
            this.jwtProcessor.setJWSKeySelector(keySelector);

            LOG.info("TokenValidator initialisé avec JWKS : " + jwksUrlStr);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Impossible de charger le RemoteJWKSet au démarrage (l'IAM n'est peut-être pas encore démarré)", e);
        }
    }

    /**
     * Valide un jeton JWT RS256 et vérifie :
     * 1. La signature cryptographique avec la clé publique du JWKS.
     * 2. L'expiration (exp).
     * 3. L'audience (aud = sbe-api).
     */
    public Optional<JWTClaimsSet> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        try {
            if (this.jwtProcessor == null) {
                init();
            }

            if (this.jwtProcessor == null) {
                LOG.warning("Validation JWT impossible : processeur non initialisé.");
                return Optional.empty();
            }

            JWTClaimsSet claims = this.jwtProcessor.process(token, null);

            // Vérification de l'audience requise par le contrat (sbe-api)
            if (claims.getAudience() == null || !claims.getAudience().contains("sbe-api")) {
                LOG.warning("Jeton rejeté : audience 'sbe-api' manquante.");
                return Optional.empty();
            }
            // Vérification de l'émetteur OIDC attendu
            String expectedIssuer = OidcIssuerConfig.getIssuer();
            String actualIssuer = claims.getIssuer();

            if (actualIssuer == null || !Objects.equals(expectedIssuer, actualIssuer)) {
                LOG.warning("Jeton rejeté : issuer incorrect ou manquant.");
                return Optional.empty();
            }
            return Optional.of(claims);

        } catch (Exception e) {
            LOG.fine("Échec de validation du jeton RS256 : " + e.getMessage());
            return Optional.empty();
        }
    }
}