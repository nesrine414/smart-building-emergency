package com.smartbuilding.services;

import com.smartbuilding.entities.Incident;
import com.smartbuilding.events.IncidentChanged;
import com.smartbuilding.repositories.IncidentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class IncidentService {

    @Inject
    private IncidentRepository repository;

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

    public Optional<Incident> findActiveIncident(String roomId, String type) {
        return repository.findByRoomIdAndTypeAndStatus(roomId, type, "OPEN");
    }
}