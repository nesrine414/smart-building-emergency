package com.smartbuilding.config;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.net.URI;
import java.util.List;

@Provider
public class CorsFilter implements ContainerResponseFilter {

    private static final List<String> ALLOWED_ORIGINS = List.of(
        "http://www.smartbuilding.local:4200",
        "https://www.smartbuilding.local:4200",
        "http://admin.smartbuilding.local:4201",
        "https://admin.smartbuilding.local:4201"
    );

    private boolean isAllowedOrigin(String origin) {
        if (origin == null) {
            return false;
        }

        if (ALLOWED_ORIGINS.contains(origin)) {
            return true;
        }

        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            int port = uri.getPort();

            boolean localHost = "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host);

            boolean httpScheme = "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme);

            boolean validPort = port == -1 || (port >= 1 && port <= 65535);

            return localHost
                    && httpScheme
                    && validPort
                    && uri.getRawPath().isEmpty()
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public void filter(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext
    ) throws IOException {

        String origin = requestContext.getHeaderString("Origin");

        if (isAllowedOrigin(origin)) {
            responseContext.getHeaders().putSingle("Access-Control-Allow-Origin", origin);
            responseContext.getHeaders().putSingle("Access-Control-Allow-Credentials", "true");
            responseContext.getHeaders().putSingle("Vary", "Origin");
        }

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Methods",
            "GET, POST, PUT, PATCH, DELETE, OPTIONS"
        );

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, Accept, Origin, X-Requested-With"
        );

        responseContext.getHeaders().putSingle("Access-Control-Max-Age", "3600");
    }
}