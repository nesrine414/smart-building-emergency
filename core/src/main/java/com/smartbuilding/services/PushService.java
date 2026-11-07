package com.smartbuilding.services;

import com.smartbuilding.entities.Incident;
import com.smartbuilding.entities.PushSubscription;
import com.smartbuilding.events.IncidentChanged;
import com.smartbuilding.repositories.PushSubscriptionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class PushService {

    private static final Logger LOG = Logger.getLogger(PushService.class.getName());
    private final Jsonb jsonb = JsonbBuilder.create();

    @Inject
    @ConfigProperty(name = "vapid.public.key", defaultValue = "")
    private String publicKey;

    @Inject
    @ConfigProperty(name = "vapid.private.key", defaultValue = "")
    private String privateKey;

    @Inject
    @ConfigProperty(name = "vapid.subject", defaultValue = "mailto:admin@smartbuilding.com")
    private String subject;

    @Inject
    private PushSubscriptionRepository repository;

    public String getPublicKey() {
        return publicKey;
    }

    public void saveSubscription(PushSubscription subscription) {
        if (subscription != null && subscription.getEndpoint() != null) {
            repository.save(subscription);
            LOG.info("Abonnement Web Push enregistré pour la salle : " + subscription.getRoomId());
        }
    }

    public void deleteSubscription(String endpoint) {
        if (endpoint != null) {
            repository.deleteById(endpoint);
            LOG.info("Abonnement Web Push supprimé : " + endpoint);
        }
    }

    // Réaction automatique lors d'un incident
    public void onIncidentChanged(@Observes IncidentChanged event) {
        Incident incident = event.incident();
        if ("RESOLVED".equals(incident.getStatus())) {
            return;
        }

        LOG.info("Envoi des notifications Push suite à l'incident " + incident.getId() + " (" + incident.getType() + ")");

        try (var stream = repository.findAll()) {
            stream.forEach(sub -> {
                // Déterminer le message selon l'étage
                String title;
                String body;

                // Si l'occupant est sur la même zone/bâtiment
                if (incident.getRoomId() != null && incident.getRoomId().equals(sub.getRoomId())) {
                    title = "🚨 DANGER IMMÉDIAT : " + incident.getType();
                    body = incident.getRecommendation() != null ? incident.getRecommendation() : "Évacuation immédiate de la salle !";
                } else {
                    title = "⚠️ ALERTE : " + incident.getType() + " dans le bâtiment";
                    body = "Incident détecté en " + incident.getRoomId() + ". Restez vigilants.";
                }

                sendPush(sub, title, body, "/incidents/" + incident.getId());
            });
        }
    }

    private void sendPush(PushSubscription sub, String title, String body, String url) {
        // Préparer le payload au format du Service Worker d'Angular (Contrat 8)
        Map<String, Object> notificationData = new HashMap<>();
        notificationData.put("title", title);
        notificationData.put("body", body);
        notificationData.put("data", Map.of("url", url));

        Map<String, Object> payload = Map.of("notification", notificationData);
        String payloadJson = jsonb.toJson(payload);

        LOG.info("Simulation envoi Web Push vers " + sub.getEndpoint() + " : " + payloadJson);
        // La bibliothèque nl.martijndwars.webpush.PushService enverrait la requête HTTP/2 chiffrée ici
    }
}