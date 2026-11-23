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

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
@ApplicationScoped
public class OidcKeyService {

    private static final Logger LOG = Logger.getLogger(OidcKeyService.class.getName());

    private static final String KEYSTORE_PATH_ENV = "OIDC_KEYSTORE_PATH";
    private static final String KEYSTORE_PASSWORD_ENV = "OIDC_KEYSTORE_PASSWORD";
    private static final String KEY_ALIAS = "sbe-oidc";
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String keyId; // kid : empreinte SHA-256 de la clé publique
    private String jwksJson;

    @PostConstruct
    public void init() {
        char[] password = null;

        try {
            String keystorePath = System.getenv(KEYSTORE_PATH_ENV);
            String passwordValue = System.getenv(KEYSTORE_PASSWORD_ENV);

            if (keystorePath == null || keystorePath.isBlank()) {
                throw new IllegalStateException(
                    "La variable OIDC_KEYSTORE_PATH est obligatoire."
                );
            }

            if (passwordValue == null || passwordValue.isBlank()) {
                throw new IllegalStateException(
                    "La variable OIDC_KEYSTORE_PASSWORD est obligatoire."
                );
            }

            Path path = Paths.get(keystorePath);

            if (!path.isAbsolute()) {
                throw new IllegalStateException(
                    "OIDC_KEYSTORE_PATH doit être un chemin absolu."
                );
            }

            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IllegalStateException(
                    "Keystore introuvable ou illisible : " + path
                );
            }

            password = passwordValue.toCharArray();

            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            try (java.io.InputStream is = Files.newInputStream(path)) {
                keyStore.load(is, password);
            }

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                throw new IllegalStateException(
                    "Alias OIDC introuvable dans le keystore : " + KEY_ALIAS
                );
            }

            java.security.Key key = keyStore.getKey(KEY_ALIAS, password);
            Certificate cert = keyStore.getCertificate(KEY_ALIAS);

            if (!(key instanceof RSAPrivateKey) || cert == null
                    || !(cert.getPublicKey() instanceof RSAPublicKey)) {
                throw new IllegalStateException(
                    "Le keystore ne contient pas une paire de clés RSA valide."
                );
            }

            RSAPrivateKey loadedPrivateKey = (RSAPrivateKey) key;
            RSAPublicKey loadedPublicKey = (RSAPublicKey) cert.getPublicKey();

            if (loadedPublicKey.getModulus().bitLength() < 2048) {
                throw new IllegalStateException(
                    "La clé RSA doit avoir une taille minimale de 2048 bits."
                );
            }

            if (!loadedPrivateKey.getModulus().equals(loadedPublicKey.getModulus())) {
                throw new IllegalStateException(
                    "La clé privée et le certificat ne correspondent pas."
                );
            }

            this.privateKey = loadedPrivateKey;
            this.publicKey = loadedPublicKey;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(this.publicKey.getEncoded());
            this.keyId = bytesToHex(hash);

            RSAKey rsaJWK = new RSAKey.Builder(this.publicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(this.keyId)
                    .build();

            this.jwksJson = new JWKSet(rsaJWK).toString();

            LOG.info(
                "OidcKeyService initialisé avec succès. Algorithme: RS256 | kid: "
                + this.keyId
            );

        } catch (Exception e) {
            LOG.log(
                Level.SEVERE,
                "Erreur fatale lors du chargement du keystore RSA OIDC",
                e
            );

            throw new IllegalStateException(
                "Impossible de charger les clés RSA pour OIDC", e
            );

        } finally {
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
            }
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