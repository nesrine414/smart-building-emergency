package com.smartbuilding.iam;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorsFilterTest {

    private CorsFilter corsFilter;

    @BeforeEach
    void setUp() {
        corsFilter = new CorsFilter();
    }

    private Response executePreflight(
            String origin,
            String requestedMethod,
            String requestedHeaders
    ) throws Exception {

        ContainerRequestContext request =
            mock(ContainerRequestContext.class);

        when(request.getMethod()).thenReturn("OPTIONS");
        when(request.getHeaderString("Origin")).thenReturn(origin);
        when(request.getHeaderString("Access-Control-Request-Method"))
            .thenReturn(requestedMethod);
        when(request.getHeaderString("Access-Control-Request-Headers"))
            .thenReturn(requestedHeaders);

        corsFilter.filter(request);

        ArgumentCaptor<Response> captor =
            ArgumentCaptor.forClass(Response.class);

        verify(request).abortWith(captor.capture());

        return captor.getValue();
    }

    private Response executePreflight(String origin) throws Exception {
        return executePreflight(origin, "GET", null);
    }

    @Test
    void testPreflight_Localhost4200_Autorise() throws Exception {
        Response response = executePreflight("http://localhost:4200");

        assertEquals(204, response.getStatus());
        assertEquals(
            "http://localhost:4200",
            response.getHeaderString("Access-Control-Allow-Origin")
        );
        assertEquals(
            "true",
            response.getHeaderString("Access-Control-Allow-Credentials")
        );
    }

    @Test
    void testPreflight_Localhost4201_Autorise() throws Exception {
        Response response = executePreflight("http://localhost:4201");

        assertEquals(204, response.getStatus());
        assertEquals(
            "http://localhost:4201",
            response.getHeaderString("Access-Control-Allow-Origin")
        );
    }

    @Test
    void testPreflight_PortNonAutorise_Refuse() throws Exception {
        Response response = executePreflight("http://localhost:9999");

        assertEquals(403, response.getStatus());
        assertNull(
            response.getHeaderString("Access-Control-Allow-Origin")
        );
    }

    @Test
    void testPreflight_DomaineExterne_Refuse() throws Exception {
        Response response =
            executePreflight("https://evil.example.com:4200");

        assertEquals(403, response.getStatus());
    }

    @Test
    void testPreflight_SousDomaineFrauduleux_Refuse() throws Exception {
        Response response = executePreflight(
            "https://www.smartbuilding.local.attacker.com:4200"
        );

        assertEquals(403, response.getStatus());
    }

    @Test
    void testPreflight_OrigineAvecChemin_Refuse() throws Exception {
        Response response = executePreflight(
            "http://localhost:4200/attacker"
        );

        assertEquals(403, response.getStatus());
    }

    @Test
    void testPreflight_OrigineAvecQuery_Refuse() throws Exception {
        Response response = executePreflight(
            "http://localhost:4200?redirect=evil"
        );

        assertEquals(403, response.getStatus());
    }

    @Test
    void testPreflight_OrigineNulle_Refuse() throws Exception {
        Response response = executePreflight(null);

        assertEquals(403, response.getStatus());
    }

    @Test
    void testPreflight_MethodeInterdite_Refuse() throws Exception {
        Response response = executePreflight(
            "http://localhost:4200",
            "TRACE",
            null
        );

        assertEquals(403, response.getStatus());
        assertNull(
            response.getHeaderString("Access-Control-Allow-Origin")
        );
    }

    @Test
    void testPreflight_HeaderNonAutorise_Refuse() throws Exception {
        Response response = executePreflight(
            "http://localhost:4200",
            "POST",
            "authorization, x-evil-header"
        );

        assertEquals(403, response.getStatus());
        assertNull(
            response.getHeaderString("Access-Control-Allow-Origin")
        );
    }

    @Test
    void testResponseFilter_OrigineAutorisee_AjouteHeaders()
            throws Exception {

        ContainerRequestContext request =
            mock(ContainerRequestContext.class);

        ContainerResponseContext responseContext =
            mock(ContainerResponseContext.class);

        MultivaluedMap<String, Object> headers =
            new MultivaluedHashMap<>();

        when(request.getHeaderString("Origin"))
            .thenReturn("http://localhost:4200");

        when(responseContext.getHeaders()).thenReturn(headers);

        corsFilter.filter(request, responseContext);

        assertEquals(
            "http://localhost:4200",
            headers.getFirst("Access-Control-Allow-Origin")
        );

        assertEquals(
            "true",
            headers.getFirst("Access-Control-Allow-Credentials")
        );
    }

    @Test
    void testResponseFilter_OrigineInterdite_NAjoutePasAllowOrigin()
            throws Exception {

        ContainerRequestContext request =
            mock(ContainerRequestContext.class);

        ContainerResponseContext responseContext =
            mock(ContainerResponseContext.class);

        MultivaluedMap<String, Object> headers =
            new MultivaluedHashMap<>();

        when(request.getHeaderString("Origin"))
            .thenReturn("https://evil.example.com");

        when(responseContext.getHeaders()).thenReturn(headers);

        corsFilter.filter(request, responseContext);

        assertNull(headers.getFirst("Access-Control-Allow-Origin"));
        assertNull(headers.getFirst("Access-Control-Allow-Credentials"));
    }
}