package com.smartbuilding.iam;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.net.URI;
import java.util.List;

@Provider
@PreMatching
public class CorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final List<String> ALLOWED_ORIGINS = List.of(
        "http://www.smartbuilding.local:4200",
        "https://www.smartbuilding.local:4200",
        "http://admin.smartbuilding.local:4201",
        "https://admin.smartbuilding.local:4201"
    );

        private boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }

        // 1. Variable d'environnement CORS_ORIGINS (ex: "https://site1.com,https://site2.com")
        String envOrigins = System.getenv("CORS_ORIGINS");
        if (envOrigins != null && !envOrigins.isBlank()) {
            for (String allowed : envOrigins.split(",")) {
                if (origin.equalsIgnoreCase(allowed.trim())) {
                    return true;
                }
            }
        }

        // 2. Liste d'origines par défaut
        if (ALLOWED_ORIGINS.contains(origin)) {
            return true;
        }

        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            String scheme = uri.getScheme();

            boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpScheme || host == null) {
                return false;
            }

            // 3. Autoriser localhost / 127.0.0.1
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
                return true;
            }

            // 4. Autoriser tous les sous-domaines du projet (dev et prod)
            if (host.endsWith("smartbuilding.local") || host.endsWith("smartbuilding.me")) {
                return true;
            }

            return false;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void addCorsHeaders(
            Response.ResponseBuilder response,
            String origin
    ) {
        response.header("Access-Control-Allow-Origin", origin);
        response.header("Access-Control-Allow-Credentials", "true");
        response.header(
            "Access-Control-Allow-Methods",
            "GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD"
        );
        response.header(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, Accept, Origin, X-Requested-With"
        );
        response.header("Access-Control-Max-Age", "3600");
        response.header(
            "Vary",
            "Origin, Access-Control-Request-Method, Access-Control-Request-Headers"
        );
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        String origin = requestContext.getHeaderString("Origin");

        if (!isAllowedOrigin(origin)) {
            requestContext.abortWith(
                Response.status(Response.Status.FORBIDDEN).build()
            );
            return;
        }

        Response.ResponseBuilder response = Response.noContent();
        addCorsHeaders(response, origin);
        requestContext.abortWith(response.build());
    }

    @Override
    public void filter(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext
    ) throws IOException {
        String origin = requestContext.getHeaderString("Origin");

        if (isAllowedOrigin(origin)) {
            responseContext.getHeaders().putSingle(
                "Access-Control-Allow-Origin", origin
            );
            responseContext.getHeaders().putSingle(
                "Access-Control-Allow-Credentials", "true"
            );
            responseContext.getHeaders().putSingle("Vary", "Origin");
        }

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Methods",
            "GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD"
        );
        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, Accept, Origin, X-Requested-With"
        );
    }
}