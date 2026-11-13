package com.smartbuilding.services;

import com.smartbuilding.entities.Building;
import com.smartbuilding.entities.Room;
import com.smartbuilding.entities.Sensor;
import com.smartbuilding.repositories.BuildingRepository;
import com.smartbuilding.repositories.RoomRepository;
import com.smartbuilding.repositories.SensorRepository;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class BuildingManager {

    @Inject
    private BuildingRepository buildingRepository;

    @Inject
    private RoomRepository roomRepository;

    @Inject
    private SensorRepository sensorRepository;

    @PostConstruct
    public void init() {
        // Initialiser bld-01 s'il n'existe pas
        try (var stream = buildingRepository.findAll()) {
            if (stream.findAny().isEmpty()) {
                buildingRepository.save(new Building("bld-01", "Main Tower", "Avenue Habib Bourguiba", 12));
                buildingRepository.save(new Building("bld-02", "Innovation Center", "Technopole Ghazela", 5));
                buildingRepository.save(new Building("bld-03", "Warehouse A", "Zone Industrielle", 2));
            }
        }

        // Initialiser les 3 salles (contrats 3 & 7)
        try (var stream = roomRepository.findAll()) {
            if (stream.findAny().isEmpty()) {
                roomRepository.save(new Room("room-101", "bld-01", "101", 1, 5, 10, 40, 35, "Escalier A"));
                roomRepository.save(new Room("room-102", "bld-01", "102", 1, 55, 10, 40, 35, "Escalier B"));
                roomRepository.save(new Room("room-201", "bld-01", "201", 2, 5, 10, 40, 35, "Escalier A"));
            }
        }

        // Initialiser les 15 capteurs (5 par salle : gas, smoke, temp, hum, motion)
        try (var stream = sensorRepository.findAll()) {
            if (stream.findAny().isEmpty()) {
                String[] roomIds = {"room-101", "room-102", "room-201"};
                String[] types = {"GAS", "SMOKE", "TEMPERATURE", "HUMIDITY", "MOTION"};
                String[] prefixes = {"gas", "smoke", "temp", "hum", "motion"};

                for (String rId : roomIds) {
                    String num = rId.replace("room-", "");
                    for (int i = 0; i < types.length; i++) {
                        String sId = prefixes[i] + "-" + num;
                        sensorRepository.save(new Sensor(sId, rId, types[i], "ACTIVE"));
                    }
                }
            }
        }
    }

    public List<Building> getAllBuildings() {
        try (var stream = buildingRepository.findAll()) {
            return stream.toList();
        }
    }

    public List<Room> getRoomsByBuilding(String buildingId) {
        return roomRepository.findByBuildingId(buildingId);
    }

    public List<Sensor> getSensors(String roomId) {
        if (roomId != null && !roomId.isBlank()) {
            return sensorRepository.findByRoomId(roomId);
        }
        try (var stream = sensorRepository.findAll()) {
            return stream.toList();
        }
    }
}