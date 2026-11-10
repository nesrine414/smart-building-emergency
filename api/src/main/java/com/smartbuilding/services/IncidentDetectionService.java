package com.smartbuilding.services;

import com.smartbuilding.detection.Detection;
import com.smartbuilding.detection.DetectionEngine;
import com.smartbuilding.entities.Incident;
import com.smartbuilding.entities.Room;
import com.smartbuilding.repositories.RoomRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

@ApplicationScoped
public class IncidentDetectionService {

    private static final Logger LOG =
            Logger.getLogger(IncidentDetectionService.class.getName());

    @Inject
    private IncidentService incidentService;

    @Inject
    private RoomRepository roomRepository;

    public synchronized Incident openOrUpdate(Detection d) {
        Optional<Incident> active =
                incidentService.findAll(null).stream()
                        .filter(i -> d.roomId().equals(i.getRoomId())
                                && d.type().equals(i.getType()))
                        .filter(i -> !"RESOLVED".equals(i.getStatus()))
                        .max(Comparator.comparingLong(Incident::getTimestamp));

        Room origin = findRoom(d.roomId());

        if (active.isEmpty()) {
            Incident incident = new Incident();
            incident.setBuildingId(d.buildingId());
            incident.setRoomId(d.roomId());
            incident.setSensorId(d.sensorId());
            incident.setType(d.type());
            incident.setSeverity(d.severity());
            incident.setStatus("OPEN");
            incident.setOccupied(d.occupied());
            incident.setDescription(IncidentAdvisor.description(d, origin));
            incident.setAffectedRoomIds(
                    IncidentAdvisor.affectedRoomIds(d.roomId(), origin, allRooms()));
            incident.setRecommendation(
                    IncidentAdvisor.recommendation(d.type(), d.roomId(), origin));

            Incident saved = incidentService.save(incident);

            LOG.info("Incident créé : " + saved.getType()
                    + " " + saved.getSeverity()
                    + " " + saved.getRoomId());

            return saved;
        }

        Incident incident = active.get();

        boolean moreSevere =
                DetectionEngine.compareSeverity(
                        d.severity(), incident.getSeverity()) > 0;

        boolean nowOccupied =
                d.occupied() && !incident.isOccupied();

        if (!moreSevere && !nowOccupied) {
            return incident;
        }

        if (moreSevere) {
            incident.setSeverity(d.severity());
        }

        incident.setOccupied(incident.isOccupied() || d.occupied());
        incident.setSensorId(d.sensorId());

        incident.setDescription(IncidentAdvisor.description(
                new Detection(
                        d.buildingId(),
                        d.roomId(),
                        d.sensorId(),
                        d.type(),
                        incident.getSeverity(),
                        incident.isOccupied(),
                        d.measured(),
                        d.timestamp()
                ),
                origin
        ));

        Incident saved = incidentService.save(incident);

        LOG.info("Incident mis à jour : " + saved.getType()
                + " " + saved.getSeverity()
                + " " + saved.getRoomId());

        return saved;
    }

    private Room findRoom(String roomId) {
        try {
            return roomRepository.findById(roomId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Room> allRooms() {
        try (var stream = roomRepository.findAll()) {
            return stream.toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}