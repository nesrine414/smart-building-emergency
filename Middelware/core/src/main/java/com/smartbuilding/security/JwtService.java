package com.smartbuilding.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Date;
import java.util.Optional;

@ApplicationScoped
public class JwtService {

    private static final String ISSUER = "smartbuilding-iam";
    private static final long EXPIRATION_MILLIS = 8 * 3600 * 1000L; // 8 heures

    private byte[] getSecretKey() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = System.getProperty("jwt.secret", "une-cle-secrete-par-defaut-de-plus-de-32-caracteres-pour-le-dev");
        }
        return secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Génère un jeton JWT signé avec HS256.
     */
    public String generateToken(String userId, String username, String name, String role) {
        try {
            JWSSigner signer = new MACSigner(getSecretKey());

            long now = System.currentTimeMillis();
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(userId)
                    .claim("username", username)
                    .claim("name", name)
                    .claim("role", role)
                    .issuer(ISSUER)
                    .issueTime(new Date(now))
                    .expirationTime(new Date(now + EXPIRATION_MILLIS))
                    .build();

            SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
            signedJWT.sign(signer);

            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de générer le jeton JWT", e);
        }
    }

    /**
     * Vérifie et extrait les claims d'un jeton JWT.
     * Retourne Optional.empty() si le jeton est invalide, altéré ou expiré.
     */
    public Optional<JWTClaimsSet> validateToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(getSecretKey());

            if (!signedJWT.verify(verifier)) {
                return Optional.empty(); // Signature non valide
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.before(new Date())) {
                return Optional.empty(); // Jeton expiré
            }

            if (!ISSUER.equals(claims.getIssuer())) {
                return Optional.empty();
            }

            return Optional.of(claims);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}