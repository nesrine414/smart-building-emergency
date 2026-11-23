package com.smartbuilding.security;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OidcIssuerConfigTest {

    @Test
    void utiliseIssuerHttpsLocalParDefaut() {
        assertEquals(
                "https://localhost:8444/iam/api/oauth2",
                OidcIssuerConfig.resolve(Map.of())
        );
    }

    @Test
    void privilegieOidcIssuer() {
        Map<String, String> environment = Map.of(
                "OIDC_ISSUER", "https://iam.example.com/iam/api/oauth2",
                "PUBLIC_DOMAIN", "example.com"
        );

        assertEquals(
                "https://iam.example.com/iam/api/oauth2",
                OidcIssuerConfig.resolve(environment)
        );
    }

    @Test
    void construitIssuerDepuisPublicDomain() {
        assertEquals(
                "https://iam.example.com/iam/api/oauth2",
                OidcIssuerConfig.resolve(
                        Map.of("PUBLIC_DOMAIN", "example.com")
                )
        );
    }

    @Test
    void ignoreIssuerVideEtUtiliseDomainePublic() {
        Map<String, String> environment = Map.of(
                "OIDC_ISSUER", " ",
                "PUBLIC_DOMAIN", "example.com"
        );

        assertEquals(
                "https://iam.example.com/iam/api/oauth2",
                OidcIssuerConfig.resolve(environment)
        );
    }
}