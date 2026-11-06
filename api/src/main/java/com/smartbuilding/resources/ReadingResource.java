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

    // 1. GET /readings?limit=50&sensorId=gas-101
    @GET
    public Response getReadings(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("sensorId") String sensorId) {
        
        List<SensorReading> results = readingService.findLatest(limit, sensorId);
        return Response.ok(results).build();
    }

    // 2. GET /readings/{id}
    @GET
    @Path("/{id}")
    public Response getReadingById(@PathParam("id") String id) {
        return readingService.findById(id)
                .map(reading -> Response.ok(reading).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    // 3. POST /readings
    @POST
    public Response createReading(SensorReading reading) {
        if (reading == null || reading.getType() == null || reading.getSensorId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("sensorId et type sont obligatoires")
                    .build();
        }
        
        SensorReading saved = readingService.save(reading);
        return Response.status(Response.Status.CREATED).entity(saved).build();
    }
}