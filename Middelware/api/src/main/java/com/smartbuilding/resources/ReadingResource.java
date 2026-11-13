
package com.smartbuilding.resources;

import com.smartbuilding.entities.SensorReading;
import com.smartbuilding.services.ReadingService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/readings")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReadingResource {

    @Inject
    private ReadingService readingService;

    /**
     * GET /api/readings?limit=50&sensorId=gas-101
     * Retourne les mesures les plus récentes.
     */
    @GET
    public Response getReadings(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("sensorId") String sensorId) {

        if (limit < 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Le paramètre limit doit être supérieur à 0")
                    .build();
        }

        try {
            List<SensorReading> results =
                    readingService.findLatest(Math.min(limit, 500), sensorId);

            return Response.ok(results).build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erreur lors de la récupération des mesures")
                    .build();
        }
    }

    /**
     * GET /api/readings/{id}
     */
    @GET
    @Path("/{id}")
    public Response getReadingById(@PathParam("id") String id) {

        return readingService.findById(id)
                .map(reading -> Response.ok(reading).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * POST /api/readings
     */
    @POST
    public Response createReading(SensorReading reading) {

        if (reading == null
                || reading.getType() == null
                || reading.getSensorId() == null
                || reading.getSensorId().isBlank()) {

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("sensorId et type sont obligatoires")
                    .build();
        }

        try {
            SensorReading saved = readingService.save(reading);

            return Response.status(Response.Status.CREATED)
                    .entity(saved)
                    .build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erreur lors de l'enregistrement de la mesure")
                    .build();
        }
    }
}
