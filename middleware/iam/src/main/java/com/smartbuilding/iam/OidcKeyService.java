package com.smartbuilding.iam;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class OidcKeyService {

    private static final Logger LOG = Logger.getLogger(OidcKeyService.class.getName());

    private static final String KEYSTORE_RESOURCE = "oidc-keystore.p12";
    private static final String KEY_ALIAS = "sbe-oidc";

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String keyId; // kid : empreinte SHA-256 de la clé publique
    private String jwksJson;

    @PostConstruct
    public void init() {
        try {
            // Lecture du mot de passe (priorité variable d'environnement, sinon valeur par défaut)
            String password = System.getenv("OIDC_KEYSTORE_PASSWORD");
            if (password == null || password.isBlank()) {
                password = "sbeSecret2026!";
            }

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(KEYSTORE_RESOURCE)) {
                if (is == null) {
                    throw new IllegalStateException("Fichier " + KEYSTORE_RESOURCE + " introuvable dans le classpath.");
                }
                keyStore.load(is, password.toCharArray());
            }

            PrivateKey key = (PrivateKey) keyStore.getKey(KEY_ALIAS, password.toCharArray());
            Certificate cert = keyStore.getCertificate(KEY_ALIAS);

            if (!(key instanceof RSAPrivateKey) || !(cert.getPublicKey() instanceof RSAPublicKey)) {
                throw new IllegalStateException("La clé trouvée dans le keystore n'est pas une paire RSA valide.");
            }

            this.privateKey = (RSAPrivateKey) key;
            this.publicKey = (RSAPublicKey) cert.getPublicKey();

            // Calcul du kid (Empreinte SHA-256 en hexadécimal de la clé publique, exigé par le contrat)
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(this.publicKey.getEncoded());
            this.keyId = bytesToHex(hash);

            // Construction du JWK Set public (RFC 7517)
            RSAKey rsaJWK = new RSAKey.Builder(this.publicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(this.keyId)
                    .build();

            this.jwksJson = new JWKSet(rsaJWK).toJSONObject().toString();

            LOG.info("OidcKeyService initialisé avec succès. Algorithme: RS256 | kid: " + this.keyId);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Erreur fatale lors du chargement du keystore RSA OIDC", e);
            throw new IllegalStateException("Impossible de charger les clés RSA pour OIDC", e);
        }
    }

    /**
     * Retourne le catalogue JWKS contenant la clé publique pour le endpoint /jwks.
     */
    public String getJwksJson() {
        return this.jwksJson;
    }

    public String getKeyId() {
        return this.keyId;
    }

    /**
     * Génère un access_token signé RS256 (durée de vie : 15 minutes, aud : sbe-api).
     */
    public String generateAccessToken(String issuer, String userId, String username, String name, String role) {
        try {
            JWSSigner signer = new RSASSASigner(this.privateKey);

            long now = System.currentTimeMillis();
            long exp = now + (15 * 60 * 1000L); // 15 minutes

            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(userId)
                    .audience("sbe-api")
                    .claim("username", username)
                    .claim("name", name)
                    .claim("role", role)
                    .issueTime(new Date(now))
                    .expirationTime(new Date(exp))
                    .build();

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(this.keyId)
                    .type(JOSEObjectType.JWT)
                    .build();

            SignedJWT signedJWT = new SignedJWT(header, claims);
            signedJWT.sign(signer);

            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Erreur lors de la signature RS256 de l'access_token", e);
        }
    }

    /**
     * Génère un id_token signé RS256 (aud : client_id, nonce inclus).
     */
    public String generateIdToken(String issuer, String userId, String username, String name, String clientId, String nonce) {
        try {
            JWSSigner signer = new RSASSASigner(this.privateKey);

            long now = System.currentTimeMillis();
            long exp = now + (15 * 60 * 1000L); // 15 minutes

            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(userId)
                    .audience(clientId)
                    .claim("username", username)
                    .claim("name", name)
                    .issueTime(new Date(now))
                    .expirationTime(new Date(exp));

            if (nonce != null && !nonce.isBlank()) {
                builder.claim("nonce", nonce);
            }

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(this.keyId)
                    .type(JOSEObjectType.JWT)
                    .build();

            SignedJWT signedJWT = new SignedJWT(header, builder.build());
            signedJWT.sign(signer);

            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Erreur lors de la signature RS256 de l'id_token", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}