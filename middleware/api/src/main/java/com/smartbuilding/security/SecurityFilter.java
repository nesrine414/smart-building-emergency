package com.smartbuilding.security;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Optional;

@Provider
@Priority(Priorities.AUTHENTICATION)
@ApplicationScoped
public class SecurityFilter implements ContainerRequestFilter {

    @Context
    private ResourceInfo resourceInfo;

    @Inject
    private TokenValidator tokenValidator;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // Laisser passer les requêtes préliminaires CORS
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        Method method = resourceInfo.getResourceMethod();
        Class<?> resourceClass = resourceInfo.getResourceClass();

        // Si la méthode ou la classe porte @Public, accès libre
        if ((method != null && method.isAnnotationPresent(Public.class))
                || (resourceClass != null && resourceClass.isAnnotationPresent(Public.class))) {
            return;
        }

        // Extraction du header Authorization: Bearer <token>
        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            abortUnauthorized(requestContext, "En-tête Authorization manquant ou invalide.");
            return;
        }

        String token = authHeader.substring(7).trim();
        Optional<JWTClaimsSet> optClaims = tokenValidator.validate(token);

        if (optClaims.isEmpty()) {
            abortUnauthorized(requestContext, "Jeton JWT RS256 invalide ou expiré.");
            return;
        }

        JWTClaimsSet claims = optClaims.get();
        String username = (String) claims.getClaim("username");
        String role = (String) claims.getClaim("role");

        // Vérification des rôles requis via @RequireRole
        RequireRole requireRole = method != null ? method.getAnnotation(RequireRole.class) : null;
        if (requireRole == null && resourceClass != null) {
            requireRole = resourceClass.getAnnotation(RequireRole.class);
        }

        if (requireRole != null && !hasRequiredRole(role, requireRole.value())) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\":\"forbidden\",\"message\":\"Rôle insuffisant pour cette ressource.\"}")
                    .build());
            return;
        }

        // Attacher le SecurityContext à la requête
        requestContext.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return () -> username != null ? username : claims.getSubject();
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
    }

    private boolean hasRequiredRole(String userRole, String requiredRole) {
        if (userRole == null || requiredRole == null) return false;
        return userRole.equalsIgnoreCase(requiredRole);
    }

    private void abortUnauthorized(ContainerRequestContext ctx, String message) {
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .entity("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}")
                .build());
    }
}