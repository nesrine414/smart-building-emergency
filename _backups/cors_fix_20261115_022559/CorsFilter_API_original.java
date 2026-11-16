package com.smartbuilding.config;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.List;

@Provider
public class CorsFilter implements ContainerResponseFilter {

    // EXACTEMENT les origines autorisées (Front Office www:4200 et Back Office admin:4201)
    private static final List<String> ALLOWED_ORIGINS = List.of(
        "http://www.smartbuilding.local:4200",
        "https://www.smartbuilding.local:4200",
        "http://admin.smartbuilding.local:4201",
        "https://admin.smartbuilding.local:4201"
    );

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        String origin = requestContext.getHeaderString("Origin");

        // Autoriser UNIQUEMENT si l'origine fait partie de la liste stricte
        if (origin != null && ALLOWED_ORIGINS.contains(origin)) {
            responseContext.getHeaders().putSingle("Access-Control-Allow-Origin", origin);
            responseContext.getHeaders().putSingle("Access-Control-Allow-Credentials", "true");
        }
        // AUCUN fallback : si l'origine est inconnue, le header n'est pas injecté

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Methods",
            "GET, POST, PUT, PATCH, DELETE, OPTIONS"
        );

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, Accept, Origin, X-Requested-With"
        );
    }
}