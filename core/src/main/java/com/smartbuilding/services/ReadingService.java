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

        // Diffuser l'événement CDI
        readingSavedEvent.fire(new ReadingSaved(saved));

        return saved;
    }

    public List<SensorReading> findLatest(int limit, String sensorId) {
        int max = limit > 0 ? limit : 50;
        if (sensorId != null && !sensorId.isBlank()) {
            return repository.findBySensorId(sensorId).stream()
                    .limit(max)
                    .toList();
        } else {
            try (var stream = repository.findAll()) {
                return stream.limit(max).toList();
            }
        }
    }

    public Optional<SensorReading> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(id);
    }
}