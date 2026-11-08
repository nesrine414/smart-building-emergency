package com.smartbuilding.resources;

import com.smartbuilding.entities.Building;
import com.smartbuilding.entities.Incident;
import com.smartbuilding.entities.Room;
import com.smartbuilding.entities.Sensor;
import com.smartbuilding.services.ActuatorService;
import com.smartbuilding.services.BuildingManager;
import com.smartbuilding.services.IncidentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class BuildingResource {

    @Inject
    private BuildingManager buildingManager;

    @Inject
    private IncidentService incidentService;

    @Inject
    private ActuatorService actuatorService;

    // GET /api/buildings
    @GET
    @Path("/buildings")
    public List<Building> getBuildings() {
        return buildingManager.getAllBuildings();
    }

    // GET /api/buildings/{id}/rooms
    @GET
    @Path("/buildings/{id}/rooms")
    public List<Room> getRooms(@PathParam("id") String buildingId) {
        return buildingManager.getRoomsByBuilding(buildingId);
    }

    // GET /api/buildings/{id}/status (SAFE ou ALERT)
    @GET
    @Path("/buildings/{id}/status")
    public Response getBuildingStatus(@PathParam("id") String buildingId) {
        List<Incident> openIncidents = incidentService.findAll("OPEN");
        long count = openIncidents.stream()
                .filter(inc -> buildingId.equals(inc.getBuildingId()))
                .count();

        String status = count > 0 ? "ALERT" : "SAFE";
        return Response.ok(Map.of(
                "buildingId", buildingId,
                "status", status,
                "openIncidents", count
        )).build();
    }

    // GET /api/sensors?roomId=room-101
    @GET
    @Path("/sensors")
    public List<Sensor> getSensors(@QueryParam("roomId") String roomId) {
        return buildingManager.getSensors(roomId);
    }

    // POST /api/rooms/{roomId}/actuators
    @POST
    @Path("/rooms/{roomId}/actuators")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response triggerActuator(
            @PathParam("roomId") String roomId,
            Map<String, String> payload) {

        if (payload == null || !payload.containsKey("actuator") || !payload.containsKey("action")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "actuator et action sont obligatoires"))
                    .build();
        }

        String actuator = payload.get("actuator");
        String action = payload.get("action");

        try {
            actuatorService.send(roomId, actuator, action);
        } catch (IllegalArgumentException e) {
            // actionneur ou action inconnus (ex. VENTILATION OPEN)
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        return Response.ok(Map.of(
                "status", "COMMAND_SENT",
                "roomId", roomId,
                "actuator", actuator,
                "action", action
        )).build();
    }
}