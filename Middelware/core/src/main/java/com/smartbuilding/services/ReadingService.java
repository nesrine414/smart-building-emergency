package com.smartbuilding.services;

import com.smartbuilding.entities.SensorReading;
import com.smartbuilding.events.ReadingSaved;
import com.smartbuilding.repositories.SensorReadingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ReadingService {

    @Inject
    private SensorReadingRepository repository;

    @Inject
    private Event<ReadingSaved> readingSavedEvent;

    public SensorReading save(SensorReading reading) {
        if (reading.getId() == null || reading.getId().isBlank()) {
            reading.setId(UUID.randomUUID().toString());
        }
        if (reading.getTimestamp() <= 0) {
            reading.setTimestamp(System.currentTimeMillis());
        }

        SensorReading saved = repository.save(reading);
        readingSavedEvent.fire(new ReadingSaved(saved));
        return saved;
    }

    public List<SensorReading> findLatest(int limit, String sensorId) {
        int max = limit > 0 ? limit : 50;

        if (sensorId != null && !sensorId.isBlank()) {
            return repository.findBySensorId(sensorId).stream()
                    .sorted(java.util.Comparator.comparingLong(
                            SensorReading::getTimestamp).reversed())
                    .limit(max)
                    .toList();
        } else {
            try (var stream = repository.findAll()) {
                return stream
                        .sorted(java.util.Comparator.comparingLong(
                                SensorReading::getTimestamp).reversed())
                        .limit(max)
                        .toList();
            }
        }
    }

    // Mesures limitees a la salle exacte demandee.
    // Mesures filtrees par salle exacte depuis le flux existant.
    public List<SensorReading> findLatestByRoomId(String roomId, int limit) {
        if (roomId == null || roomId.isBlank()) {
            return List.of();
        }

        int max = limit > 0 ? limit : 50;

        try (var stream = repository.findAll()) {
            return stream
                    .filter(reading -> roomId.equals(reading.getRoomId()))
                    .sorted(java.util.Comparator.comparingLong(
                            SensorReading::getTimestamp).reversed())
                    .limit(max)
                    .toList();
        }
    }

    public Optional<SensorReading> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(id);
    }
}