package com.smartbuilding.resources;

import com.smartbuilding.entities.Building;
import com.smartbuilding.services.BuildingManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/buildings")
public class BuildingResource {

    @Inject
    private BuildingManager buildingManager;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Building> getBuildings() {
        return buildingManager.getAllBuildings();
    }
}
