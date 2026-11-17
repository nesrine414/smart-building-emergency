package com.smartbuilding.resources;

import com.smartbuilding.entities.Incident;
import com.smartbuilding.security.RequireRole;
import com.smartbuilding.services.IncidentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/incidents")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IncidentResource {

    @Inject
    private IncidentService incidentService;

    // 1. GET /api/incidents?status=OPEN (Connecté requis : OCCUPANT ou ADMIN)
    @GET
    public Response getIncidents(@QueryParam("status") String status) {
        List<Incident> list = incidentService.findAll(status);
        return Response.ok(list).build();
    }

    // 2. GET /api/incidents/{id} (Connecté requis : OCCUPANT ou ADMIN)
    @GET
    @Path("/{id}")
    public Response getIncidentById(@PathParam("id") String id) {
        return incidentService.findById(id)
                .map(incident -> Response.ok(incident).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    // 3. PATCH /api/incidents/{id} (Rôle ADMIN requis !)
    @PATCH
    @Path("/{id}")
    @RequireRole("ADMIN")
    public Response updateStatus(@PathParam("id") String id, Map<String, String> payload) {
        if (payload == null || !payload.containsKey("status")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Le champ 'status' est obligatoire"))
                    .build();
        }

        String newStatus = payload.get("status");
        return incidentService.updateStatus(id, newStatus)
                .map(updated -> Response.ok(updated).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    // 4. POST /api/incidents (Connecté requis)
    @POST
    public Response createIncident(Incident incident) {
        if (incident == null || incident.getType() == null || incident.getRoomId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "type et roomId sont obligatoires"))
                    .build();
        }
        Incident created = incidentService.save(incident);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }
}