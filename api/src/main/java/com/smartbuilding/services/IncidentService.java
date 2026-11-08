package com.smartbuilding.services;

import com.smartbuilding.detection.Detection;
import com.smartbuilding.detection.DetectionEngine;
import com.smartbuilding.entities.Incident;
import com.smartbuilding.entities.Room;
import com.smartbuilding.events.IncidentChanged;
import com.smartbuilding.repositories.IncidentRepository;
import com.smartbuilding.repositories.RoomRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

@ApplicationScoped
public class IncidentService {

    private static final Logger LOG = Logger.getLogger(IncidentService.class.getName());

    @Inject
    private IncidentRepository repository;

    @Inject
    private RoomRepository roomRepository;

    @Inject
    private Event<IncidentChanged> incidentChangedEvent;

    public List<Incident> findAll(String status) {
        if (status != null && !status.isBlank()) {
            return repository.findByStatus(status);
        }
        try (var stream = repository.findAll()) {
            return stream.toList();
        }
    }

    public Optional<Incident> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(id);
    }

    public Incident save(Incident incident) {
        boolean isNew = incident.getId() == null || incident.getId().isBlank();
        if (isNew) {
            incident.setId(UUID.randomUUID().toString());
            incident.setTimestamp(System.currentTimeMillis());
            incident.setUpdatedAt(incident.getTimestamp());
            if (incident.getStatus() == null || incident.getStatus().isBlank()) {
                incident.setStatus("OPEN");
            }
        } else {
            incident.setUpdatedAt(System.currentTimeMillis());
        }

        Incident saved = repository.save(incident);
        String changeType = isNew ? "CREATED" : ("RESOLVED".equals(saved.getStatus()) ? "RESOLVED" : "UPDATED");
        incidentChangedEvent.fire(new IncidentChanged(saved, changeType));
        return saved;
    }

    public Optional<Incident> updateStatus(String id, String newStatus) {
        Optional<Incident> opt = findById(id);
        if (opt.isEmpty()) {
            return Optional.empty();
        }

        Incident incident = opt.get();
        incident.setStatus(newStatus);
        incident.setUpdatedAt(System.currentTimeMillis());
        Incident updated = repository.save(incident);

        String changeType = "RESOLVED".equalsIgnoreCase(newStatus) ? "RESOLVED" : "UPDATED";
        incidentChangedEvent.fire(new IncidentChanged(updated, changeType));
        return Optional.of(updated);
    }

    /**
     * Incident actif d'une salle pour un type : OPEN ou ACKNOWLEDGED (tout sauf RESOLVED).
     * Un incident pris en charge reste actif : une nouvelle détection le met à jour au lieu d'en créer un autre.
     */
    public Optional<Incident> findActiveIncident(String roomId, String type) {
        try (var stream = repository.findAll()) {
            return stream
                    .filter(i -> roomId.equals(i.getRoomId()) && type.equals(i.getType()))
                    .filter(i -> !"RESOLVED".equals(i.getStatus()))
                    .max(Comparator.comparingLong(Incident::getTimestamp));
        }
    }

    // ---------- Détection (personne 1, contrats 2 et 7) ----------

    /**
     * Crée un incident à partir d'une détection, ou met à jour l'incident actif de la même salle
     * et du même type. La mise à jour n'a lieu que si la gravité monte ou si des personnes sont
     * maintenant détectées, pour ne pas envoyer un événement (WebSocket, push) toutes les 2 secondes.
     */
    public synchronized Incident openOrUpdate(Detection d) {
        Optional<Incident> active = findActiveIncident(d.roomId(), d.type());
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
            incident.setAffectedRoomIds(IncidentAdvisor.affectedRoomIds(d.roomId(), origin, allRooms()));
            incident.setRecommendation(IncidentAdvisor.recommendation(d.type(), d.roomId(), origin));
            Incident saved = save(incident);   // id, dates, événement CREATED
            LOG.info("Incident cree : " + saved.getType() + " " + saved.getSeverity() + " " + saved.getRoomId()
                    + " (id " + saved.getId() + ")");
            return saved;
        }

        Incident incident = active.get();
        boolean moreSevere = DetectionEngine.compareSeverity(d.severity(), incident.getSeverity()) > 0;
        boolean nowOccupied = d.occupied() && !incident.isOccupied();
        if (!moreSevere && !nowOccupied) {
            return incident;  // rien de nouveau
        }
        if (moreSevere) {
            incident.setSeverity(d.severity());
        }
        incident.setOccupied(incident.isOccupied() || d.occupied());
        incident.setSensorId(d.sensorId());
        incident.setDescription(IncidentAdvisor.description(
                new Detection(d.buildingId(), d.roomId(), d.sensorId(), d.type(), incident.getSeverity(),
                        incident.isOccupied(), d.measured(), d.timestamp()), origin));
        Incident saved = save(incident);       // updatedAt, événement UPDATED
        LOG.info("Incident mis a jour : " + saved.getType() + " " + saved.getSeverity() + " " + saved.getRoomId()
                + (saved.isOccupied() ? " (personnes presentes)" : ""));
        return saved;
    }

    private Room findRoom(String roomId) {
        try {
            return roomRepository.findById(roomId).orElse(null);
        } catch (RuntimeException e) {
            return null;  // référentiel absent : l'incident est créé quand même
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
