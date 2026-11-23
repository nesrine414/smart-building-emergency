package com.smartbuilding.security;

import java.util.Map;

/**
 * Centralise l'identifiant de l'emetteur des tokens OIDC.
 * La meme valeur doit etre utilisee par IAM et par API.
 */
public final class OidcIssuerConfig {

    private static final String DEFAULT_ISSUER =
            "https://localhost:8444/iam/api/oauth2";

    private OidcIssuerConfig() {
        // Classe utilitaire : aucune instance.
    }

    public static String getIssuer() {
        return resolve(System.getenv());
    }

    /**
     * Priorite :
     * 1. OIDC_ISSUER
     * 2. PUBLIC_DOMAIN
     * 3. Issuer HTTPS local par defaut
     */
    public static String resolve(Map<String, String> environment) {
        if (environment == null) {
            return DEFAULT_ISSUER;
        }

        String configuredIssuer = environment.get("OIDC_ISSUER");

        if (configuredIssuer != null && !configuredIssuer.isBlank()) {
            return configuredIssuer.trim();
        }

        String publicDomain = environment.get("PUBLIC_DOMAIN");

        if (publicDomain != null && !publicDomain.isBlank()) {
            return "https://iam." + publicDomain.trim()
                    + "/iam/api/oauth2";
        }

        return DEFAULT_ISSUER;
    }
}