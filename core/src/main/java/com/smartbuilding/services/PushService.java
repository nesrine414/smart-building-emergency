
package com.smartbuilding.services;

import com.smartbuilding.entities.Incident;
import com.smartbuilding.entities.PushSubscription;
import com.smartbuilding.events.IncidentChanged;
import com.smartbuilding.repositories.PushSubscriptionRepository;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import nl.martijndwars.webpush.Notification;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

@ApplicationScoped
public class PushService {

    private static final Logger LOG =
            Logger.getLogger(PushService.class.getName());

    @Inject
    @ConfigProperty(name = "vapid.public.key", defaultValue = "")
    private String publicKey;

    @Inject
    @ConfigProperty(name = "vapid.private.key", defaultValue = "")
    private String privateKey;

    @Inject
    @ConfigProperty(
        name = "vapid.subject",
        defaultValue = "mailto:admin@smartbuilding.com"
    )
    private String subject;

    @Inject
    private PushSubscriptionRepository repository;

    private nl.martijndwars.webpush.PushService webPush;

    private Jsonb jsonb;

    @PostConstruct
    public void initialize() {
        jsonb = JsonbBuilder.create();

        try {
            if (publicKey == null || publicKey.isBlank()
                    || privateKey == null || privateKey.isBlank()) {
                LOG.severe("Clés VAPID absentes : envoi Push désactivé.");
                return;
            }

            webPush = new nl.martijndwars.webpush.PushService(
                    publicKey.trim(),
                    privateKey.trim(),
                    subject
            );

            LOG.info("Service Web Push initialisé avec succès.");

        } catch (Exception e) {
            LOG.log(Level.SEVERE,
                    "Erreur lors de l'initialisation Web Push.", e);
        }
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void saveSubscription(PushSubscription subscription) {
        if (subscription == null
                || subscription.getEndpoint() == null
                || subscription.getEndpoint().isBlank()
                || subscription.getP256dh() == null
                || subscription.getP256dh().isBlank()
                || subscription.getAuth() == null
                || subscription.getAuth().isBlank()) {

            throw new IllegalArgumentException(
                    "Abonnement Push incomplet."
            );
        }

        repository.save(subscription);

        LOG.info("Abonnement Push enregistré pour la salle : "
                + subscription.getRoomId());
    }

    public void deleteSubscription(String endpoint) {
        if (endpoint != null && !endpoint.isBlank()) {
            repository.deleteById(endpoint);
            LOG.info("Abonnement Push supprimé.");
        }
    }

    public void onIncidentChanged(
            @Observes IncidentChanged event) {

        if (event == null || event.incident() == null) {
            return;
        }

        if (!"CREATED".equalsIgnoreCase(event.change())) {
            return;
        }

        Incident incident = event.incident();

        if ("RESOLVED".equalsIgnoreCase(incident.getStatus())) {
            return;
        }

        if (webPush == null) {
            LOG.severe(
                    "Envoi impossible : service Web Push non initialisé."
            );
            return;
        }

        try (Stream<PushSubscription> subscriptions =
                     repository.findAll()) {

            subscriptions.forEach(subscription -> {
                String title;
                String body;

                if (incident.getRoomId() != null
                        && incident.getRoomId().equals(
                                subscription.getRoomId())) {

                    title = "DANGER IMMEDIAT : " + incident.getType();

                    body = incident.getRecommendation() != null
                            ? incident.getRecommendation()
                            : "Evacuation immediate de la salle !";

                } else if (incident.getBuildingId() != null
                        && incident.getBuildingId().equals(
                                subscription.getBuildingId())) {

                    title = "ALERTE : " + incident.getType()
                            + " dans le bâtiment";

                    body = "Incident détecté dans la salle "
                            + incident.getRoomId()
                            + ". Restez vigilants.";

                } else {
                    return;
                }

                sendPush(
                        subscription,
                        title,
                        body,
                        "/"
                );
            });

        } catch (Exception e) {
            LOG.log(Level.SEVERE,
                    "Erreur lors de la récupération des abonnements.", e);
        }
    }

    private void sendPush(
            PushSubscription subscription,
            String title,
            String body,
            String url) {

        try {
            if (webPush == null) {
                LOG.warning("Service Web Push indisponible.");
                return;
            }

            Map<String, Object> notification = Map.of(
                    "title", title,
                    "body", body,
                    "data", Map.of("url", url)
            );

            String payload = jsonb.toJson(
                    Map.of("notification", notification)
            );

            Notification pushNotification = new Notification(
                    subscription.getEndpoint(),
                    subscription.getP256dh(),
                    subscription.getAuth(),
                    payload
            );

            var response = webPush.send(pushNotification);

            int status = response.getStatusLine().getStatusCode();

            if (status >= 200 && status < 300) {
                LOG.info("Notification Push envoyée. HTTP " + status);

            } else {
                LOG.warning("Échec notification Push. HTTP " + status);

                if (status == 404 || status == 410) {
                    deleteSubscription(subscription.getEndpoint());
                    LOG.info("Abonnement expiré supprimé.");
                }
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE,
                    "Erreur lors de l'envoi de la notification Push.", e);
        }
    }
}
