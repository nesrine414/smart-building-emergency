package com.smartbuilding.services;

import com.smartbuilding.entities.Building;
import com.smartbuilding.repositories.BuildingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class BuildingManager {

    // To be wired once the MongoDB provider/repository implementation is configured:
    // @Inject
    // private BuildingRepository buildingRepository;

    public List<Building> getAllBuildings() {
        return List.of(
            new Building("bld-01", "Main Tower", "Avenue Habib Bourguiba", 12),
            new Building("bld-02", "Innovation Center", "Technopole Ghazela", 5),
            new Building("bld-03", "Warehouse A", "Zone Industrielle", 2)
        );
    }
}
