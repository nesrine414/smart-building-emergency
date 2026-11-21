package com.smartbuilding.iam;

import com.smartbuilding.entities.User;
import com.smartbuilding.repositories.UserRepository;
import com.smartbuilding.security.PasswordUtil;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class OAuth2ResourceTest {

    private OAuth2Resource oauth2Resource;
    private OidcKeyService keyService;
    private UserRepository userRepository;
    private UriInfo uriInfo;

    private final String codeVerifier = "mon-super-code-verifier-secret-tres-long-et-securise-123456789";
    private String codeChallenge;

    @BeforeEach
    public void setUp() throws Exception {
        // 1. Initialiser le vrai service de clés RSA
        keyService = new OidcKeyService();
        keyService.init();

        // 2. Mocker le UserRepository avec un utilisateur de test
        userRepository = Mockito.mock(UserRepository.class);
        User testUser = new User(
                "user-123",
                "occupant",
                PasswordUtil.hashPassword("occupant123"),
                "OCCUPANT",
                "Test Occupant"
        );
        when(userRepository.findByUsername("occupant")).thenReturn(Optional.of(testUser));

                // 3. Mocker UriInfo
        uriInfo = Mockito.mock(UriInfo.class);
        when(uriInfo.getBaseUriBuilder()).thenAnswer(inv -> UriBuilder.fromUri("http://localhost:8081/iam/api/"));
        // 4. Instancier la ressource OAuth2 et injecter les dépendances
        oauth2Resource = new OAuth2Resource();
        injectField(oauth2Resource, "keyService", keyService);
        injectField(oauth2Resource, "userRepository", userRepository);

        // 5. Calculer le code_challenge attendu : BASE64URL(SHA-256(verifier))
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Helper pour obtenir un code d'autorisation valide.
     */
    private String getValidAuthorizationCode() {
        Response response = oauth2Resource.processLogin(
                "occupant",
                "occupant123",
                "sbe-front",
                "http://localhost:4200/callback",
                "state123",
                "nonce123",
                codeChallenge,
                "S256"
        );

        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        URI location = response.getLocation();
        String query = location.getQuery();
        assertTrue(query.contains("code="));

        // Extraire le code de la query string
        for (String param : query.split("&")) {
            if (param.startsWith("code=")) {
                return param.substring(5);
            }
        }
        fail("Code non trouvé dans la redirection");
        return null;
    }

    @Test
    public void testCasNominal_EchangeCodeReussi() {
        String code = getValidAuthorizationCode();

        Response tokenResponse = oauth2Resource.exchangeToken(
                "authorization_code",
                code,
                "http://localhost:4200/callback",
                "sbe-front",
                codeVerifier,
                null,
                uriInfo
        );

        assertEquals(Response.Status.OK.getStatusCode(), tokenResponse.getStatus());
        String body = (String) tokenResponse.getEntity();

        assertTrue(body.contains("access_token"));
        assertTrue(body.contains("id_token"));
        assertTrue(body.contains("refresh_token"));
        assertTrue(body.contains("Bearer"));
        assertEquals("no-store", tokenResponse.getHeaderString("Cache-Control"));
    }

    @Test
    public void testAttaque_FauxCodeVerifier_Rejete() {
        String code = getValidAuthorizationCode();

        Response tokenResponse = oauth2Resource.exchangeToken(
                "authorization_code",
                code,
                "http://localhost:4200/callback",
                "sbe-front",
                "mauvais-code-verifier-attaque",
                null,
                uriInfo
        );

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), tokenResponse.getStatus());
        String body = (String) tokenResponse.getEntity();
        assertTrue(body.contains("invalid_grant"));
    }

    @Test
    public void testAttaque_CodeReutilise_RejeteAuDeuxiemeEssai() {
        String code = getValidAuthorizationCode();

        // 1er essai : succès
        Response tokenResponse1 = oauth2Resource.exchangeToken(
                "authorization_code",
                code,
                "http://localhost:4200/callback",
                "sbe-front",
                codeVerifier,
                null,
                uriInfo
        );
        assertEquals(Response.Status.OK.getStatusCode(), tokenResponse1.getStatus());

        // 2e essai avec le même code : doit être rejeté (usage unique)
        Response tokenResponse2 = oauth2Resource.exchangeToken(
                "authorization_code",
                code,
                "http://localhost:4200/callback",
                "sbe-front",
                codeVerifier,
                null,
                uriInfo
        );
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), tokenResponse2.getStatus());
        String body = (String) tokenResponse2.getEntity();
        assertTrue(body.contains("invalid_grant"));
    }

    @Test
    public void testAttaque_RedirectUriDifferente_Rejete() {
        String code = getValidAuthorizationCode();

        Response tokenResponse = oauth2Resource.exchangeToken(
                "authorization_code",
                code,
                "http://site-pirate.com/callback",
                "sbe-front",
                codeVerifier,
                null,
                uriInfo
        );

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), tokenResponse.getStatus());
        String body = (String) tokenResponse.getEntity();
        assertTrue(body.contains("invalid_grant"));
    }

    @Test
    public void testJwksEndpoint_RetourneClesPubliques() {
        Response jwksResponse = oauth2Resource.getJwks();
        assertEquals(Response.Status.OK.getStatusCode(), jwksResponse.getStatus());
        String body = (String) jwksResponse.getEntity();
        assertTrue(body.contains("keys"));
        assertTrue(body.contains("RSA"));
        assertTrue(body.contains("RS256"));
    }
}