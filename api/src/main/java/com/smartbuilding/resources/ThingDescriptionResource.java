package com.smartbuilding.resources;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Path("/td")
@ApplicationScoped
public class ThingDescriptionResource {

    // 1. GET /api/td -> Renvoie le W3C Thing Description complet
    @GET
    @Produces("application/td+json")
    public Response getThingDescription() {
        return readResourceFile("/wot/smart-building.td.jsonld", "application/td+json");
    }

    // 2. GET /api/td/schemas/{name} -> Renvoie un schéma précis (property, action ou event)
    @GET
    @Path("/schemas/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSchema(@PathParam("name") String name) {
        String path = "/wot/schemas/" + name;
        if (!path.endsWith(".json")) {
            path += ".json";
        }
        return readResourceFile(path, MediaType.APPLICATION_JSON);
    }

    private Response readResourceFile(String resourcePath, String contentType) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return Response.ok(content, contentType).build();
            }
        } catch (Exception ignored) {
        }
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\": \"Ressource non trouvée: " + resourcePath + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}