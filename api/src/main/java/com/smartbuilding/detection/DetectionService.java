package com.smartbuilding.detection;

import com.smartbuilding.events.ReadingSaved;
import com.smartbuilding.services.IncidentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applique les règles de détection à chaque mesure enregistrée (événement ReadingSaved)
 * et transmet les situations dangereuses à IncidentService.
 */
@ApplicationScoped
public class DetectionService {

    private static final Logger LOG = Logger.getLogger(DetectionService.class.getName());

    private final DetectionEngine engine = new DetectionEngine();

    @Inject
    IncidentService incidentService;

    void onReading(@Observes ReadingSaved event) {
        try {
            engine.analyze(event.reading()).ifPresent(incidentService::openOrUpdate);
        } catch (RuntimeException e) {
            // Une erreur de détection ne doit jamais bloquer l'enregistrement des mesures
            LOG.log(Level.WARNING, "Erreur pendant la détection", e);
        }
    }
}
