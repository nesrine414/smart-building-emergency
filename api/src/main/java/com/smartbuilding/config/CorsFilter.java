
package com.smartbuilding.config;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

@Provider
public class CorsFilter implements ContainerResponseFilter {

    @Override
    public void filter(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext
    ) throws IOException {

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Origin",
            "http://localhost:4200"
        );

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Methods",
            "GET, POST, PUT, DELETE, OPTIONS"
        );

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization"
        );

        responseContext.getHeaders().putSingle(
            "Access-Control-Allow-Credentials",
            "true"
        );
    }
}
