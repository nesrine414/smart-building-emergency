package com.smartbuilding.security;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class SecurityFilter implements ContainerRequestFilter {

    @Context
    private ResourceInfo resourceInfo;

    @Inject
    private JwtService jwtService;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // Laisser passer les requêtes OPTIONS (pré-vérification CORS)
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        Method method = resourceInfo.getResourceMethod();
        Class<?> resourceClass = resourceInfo.getResourceClass();

        // Si la ressource ou la méthode est marquée @Public, accès libre
        if (method != null && method.isAnnotationPresent(Public.class)) {
            return;
        }
        if (resourceClass != null && resourceClass.isAnnotationPresent(Public.class)) {
            return;
        }

        // Récupérer le header Authorization
        String authHeader = requestContext.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Jeton JWT manquant ou malformé (401)"))
                    .build());
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        Optional<JWTClaimsSet> optClaims = jwtService.validateToken(token);
        if (optClaims.isEmpty()) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Jeton JWT invalide ou expiré (401)"))
                    .build());
            return;
        }

        JWTClaimsSet claims = optClaims.get();
        String role = (String) claims.getClaim("role");
        String username = (String) claims.getClaim("username");
        String userId = claims.getSubject(); // Vrai identifiant sub (UUID) du compte !
        requestContext.setProperty("userId", userId);
        requestContext.setProperty("userRole", role);
        // Injecter le SecurityContext pour JAX-RS
        requestContext.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return () -> username;
            }

            @Override
            public boolean isUserInRole(String r) {
                return role != null && role.equalsIgnoreCase(r);
            }

            @Override
            public boolean isSecure() {
                return requestContext.getSecurityContext().isSecure();
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        });

        // Vérifier si un rôle spécifique est requis (ex: @RequireRole("ADMIN"))
        RequireRole requireRole = method != null ? method.getAnnotation(RequireRole.class) : null;
        if (requireRole == null && resourceClass != null) {
            requireRole = resourceClass.getAnnotation(RequireRole.class);
        }

        if (requireRole != null) {
            String expectedRole = requireRole.value();
            if (!expectedRole.equalsIgnoreCase(role)) {
                requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Accès refusé : rôle " + expectedRole + " requis (403)"))
                        .build());
            }
        }
    }
}