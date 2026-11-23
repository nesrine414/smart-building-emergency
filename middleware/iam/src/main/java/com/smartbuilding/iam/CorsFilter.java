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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Provider
@PreMatching
public class CorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(
        "http://localhost:4200",
        "https://localhost:4200",
        "http://localhost:4201",
        "https://localhost:4201",
        "http://127.0.0.1:4200",
        "https://127.0.0.1:4200",
        "http://127.0.0.1:4201",
        "https://127.0.0.1:4201",
        "http://www.smartbuilding.local:4200",
        "https://www.smartbuilding.local:4200",
        "http://admin.smartbuilding.local:4201",
        "https://admin.smartbuilding.local:4201",
        "https://www.sbe-team.me",
        "https://admin.sbe-team.me"
    );

    private static final Set<String> ALLOWED_METHODS = Set.of(
        "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"
    );

    private static final Set<String> ALLOWED_HEADERS = Set.of(
        "content-type",
        "authorization",
        "accept",
        "origin",
        "x-requested-with"
    );

    private String normalizeOrigin(String origin) {
        if (origin == null || origin.isBlank() || "null".equals(origin.trim())) {
            return null;
        }

        try {
            URI uri = URI.create(origin.trim());

            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (scheme == null || host == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                return null;
            }

            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);

            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }

            int port = uri.getPort();

            if (port == -1) {
                port = "https".equals(scheme) ? 443 : 80;
            }

            if (port < 1 || port > 65535) {
                return null;
            }

            return scheme + "://" + host + ":" + port;

        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isAllowedOrigin(String origin) {
        String normalizedOrigin = normalizeOrigin(origin);

        if (normalizedOrigin == null) {
            return false;
        }

        String envOrigins = System.getenv("CORS_ORIGINS");

        List<String> allowedOrigins;

        if (envOrigins != null && !envOrigins.isBlank()) {
            allowedOrigins = Arrays.stream(envOrigins.split(","))
                .map(String::trim)
                .map(this::normalizeOrigin)
                .filter(value -> value != null)
                .toList();
        } else {
            allowedOrigins = DEFAULT_ALLOWED_ORIGINS.stream()
                .map(this::normalizeOrigin)
                .toList();
        }

        return allowedOrigins.contains(normalizedOrigin);
    }

    private boolean isAllowedPreflight(ContainerRequestContext requestContext) {
        String requestedMethod = requestContext.getHeaderString(
            "Access-Control-Request-Method"
        );

        if (requestedMethod == null
                || !ALLOWED_METHODS.contains(requestedMethod.toUpperCase(Locale.ROOT))) {
            return false;
        }

        String requestedHeaders = requestContext.getHeaderString(
            "Access-Control-Request-Headers"
        );

        if (requestedHeaders == null || requestedHeaders.isBlank()) {
            return true;
        }

        return Arrays.stream(requestedHeaders.split(","))
            .map(String::trim)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .allMatch(ALLOWED_HEADERS::contains);
    }

    private void addCorsHeaders(
            Response.ResponseBuilder response,
            String origin,
            boolean preflight
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
            preflight
                ? "Origin, Access-Control-Request-Method, Access-Control-Request-Headers"
                : "Origin"
        );
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }

        String origin = requestContext.getHeaderString("Origin");

        if (!isAllowedOrigin(origin) || !isAllowedPreflight(requestContext)) {
            requestContext.abortWith(
                Response.status(Response.Status.FORBIDDEN)
                    .header("Vary",
                        "Origin, Access-Control-Request-Method, Access-Control-Request-Headers")
                    .build()
            );
            return;
        }

        Response.ResponseBuilder response = Response.noContent();
        addCorsHeaders(response, origin.trim(), true);
        requestContext.abortWith(response.build());
    }

    @Override
    public void filter(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext
    ) throws IOException {
        String origin = requestContext.getHeaderString("Origin");
        boolean preflight = "OPTIONS".equalsIgnoreCase(requestContext.getMethod());

        if (isAllowedOrigin(origin)) {
            responseContext.getHeaders().putSingle(
                "Access-Control-Allow-Origin", origin.trim()
            );
            responseContext.getHeaders().putSingle(
                "Access-Control-Allow-Credentials", "true"
            );
            responseContext.getHeaders().putSingle(
                "Access-Control-Allow-Methods",
                "GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD"
            );
            responseContext.getHeaders().putSingle(
                "Access-Control-Allow-Headers",
                "Content-Type, Authorization, Accept, Origin, X-Requested-With"
            );
            responseContext.getHeaders().putSingle(
                "Vary",
                preflight
                    ? "Origin, Access-Control-Request-Method, Access-Control-Request-Headers"
                    : "Origin"
            );
        } else {
            responseContext.getHeaders().putSingle("Vary", "Origin");
        }
    }
}
