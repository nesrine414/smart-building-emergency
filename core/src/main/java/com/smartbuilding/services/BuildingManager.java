package com.smartbuilding.services;

import com.smartbuilding.entities.Building;
import com.smartbuilding.repositories.BuildingRepository;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class BuildingManager {

    @Inject
    private BuildingRepository buildingRepository;

    @PostConstruct
    public void init() {
        // Initialiser avec des données de test si la collection est vide
        try (var stream = buildingRepository.findAll()) {
            if (stream.findAny().isEmpty()) {
                buildingRepository.save(new Building("bld-01", "Main Tower", "Avenue Habib Bourguiba", 12));
                buildingRepository.save(new Building("bld-02", "Innovation Center", "Technopole Ghazela", 5));
                buildingRepository.save(new Building("bld-03", "Warehouse A", "Zone Industrielle", 2));
            }
        }
    }

    public List<Building> getAllBuildings() {
        try (var stream = buildingRepository.findAll()) {
            return stream.toList();
        }
    }
}