
package com.smartbuilding.config;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.List;

@Provider
public class CorsFilter implements ContainerResponseFilter {

    private static final List<String> ALLOWED_ORIGINS = List.of(
        "http://localhost:4200",
        "http://localhost:4000"
    );

    @Override
    public void filter(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext
    ) throws IOException {

        String origin = requestContext.getHeaderString("Origin");

        if (origin != null && ALLOWED_ORIGINS.contains(origin)) {
            responseContext.getHeaders().putSingle(
                "Access-Control-Allow-Origin",
                origin
            );

            responseContext.getHeaders().putSingle(
                "Access-Control-Allow-Credentials",
                "true"
            );

            responseContext.getHeaders().putSingle(
                "Vary",
                "Origin"
            );
        }

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Methods",
            "GET, POST, PUT, PATCH, DELETE, OPTIONS"
        );

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization"
        );
    }
}
