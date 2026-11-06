package com.smartbuilding.services;

import com.smartbuilding.entities.SensorReading;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Point de jonction entre la réception MQTT, l'API REST et le stockage (contrat 5 du plan).
 *
 * VERSION PROVISOIRE : les mesures sont gardées en mémoire (perdues au redémarrage).
 * La personne 2 remplacera l'intérieur de cette classe par SensorReadingRepository (MongoDB)
 * sans changer les signatures de save() et findLatest().
 */
@ApplicationScoped
public class ReadingService {

    private static final int MAX_READINGS = 1000;

    private final Deque<SensorReading> readings = new ConcurrentLinkedDeque<>();

    public SensorReading save(SensorReading reading) {
        if (reading.getId() == null || reading.getId().isBlank()) {
            reading.setId(UUID.randomUUID().toString());
        }
        if (reading.getTimestamp() <= 0) {
            reading.setTimestamp(System.currentTimeMillis());
        }
        readings.addFirst(reading);
        while (readings.size() > MAX_READINGS) {
            readings.pollLast();
        }
        return reading;
    }

    /**
     * Les mesures les plus récentes en premier.
     *
     * @param limit    nombre maximum de mesures (50 si 0 ou négatif)
     * @param sensorId filtre optionnel (null = tous les capteurs)
     */
    public List<SensorReading> findLatest(int limit, String sensorId) {
        int max = limit > 0 ? limit : 50;
        return readings.stream()
                .filter(r -> sensorId == null || sensorId.equals(r.getSensorId()))
                .sorted(Comparator.comparingLong(SensorReading::getTimestamp).reversed())
                .limit(max)
                .toList();
    }
}
