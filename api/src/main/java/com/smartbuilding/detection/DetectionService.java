package com.smartbuilding.detection;

import com.smartbuilding.events.ReadingSaved;
import com.smartbuilding.services.IncidentDetectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class DetectionService {

    private static final Logger LOG =
            Logger.getLogger(DetectionService.class.getName());

    private final DetectionEngine engine = new DetectionEngine();

    @Inject
    IncidentDetectionService incidentDetectionService;

    void onReading(@Observes ReadingSaved event) {
        try {
            engine.analyze(event.reading())
                    .ifPresent(incidentDetectionService::openOrUpdate);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Erreur pendant la détection", e);
        }
    }
}