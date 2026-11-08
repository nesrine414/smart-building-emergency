package com.smartbuilding.services;

import com.smartbuilding.detection.Detection;
import com.smartbuilding.entities.Incident;
import com.smartbuilding.entities.Room;
import com.smartbuilding.events.IncidentChanged;
import com.smartbuilding.mqtt.ActuatorCommand;
import com.smartbuilding.mqtt.MqttListener;
import com.smartbuilding.repositories.RoomRepository;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Commande des actionneurs par MQTT (contrat 9).
 *
 * - Commande manuelle : send(roomId, actuator, action), appelée par POST /api/rooms/{roomId}/actuators.
 * - Réactions automatiques à la création d'un incident :
 *     GAS_LEAK -> VENTILATION ON + GAS_VALVE CLOSE
 *     FIRE     -> SIREN ON + EMERGENCY_EXIT OPEN
 *
 * La commande est publiée sur smartbuilding/{buildingId}/{roomId}/cmd. L'appareil (ou le simulateur)
 * confirme sur .../actuator : c'est cette confirmation, reçue par MqttListener, qui met à jour
 * ActuatorStateService et lance ActuatorChanged (WebSocket). Une commande identique à l'état déjà
 * confirmé n'est pas renvoyée.
 */
@ApplicationScoped
public class MqttActuatorService implements ActuatorService {

    private static final Logger LOG = Logger.getLogger(MqttActuatorService.class.getName());
    private static final String DEFAULT_BUILDING = "bld-01";

    @Inject
    private MqttListener mqtt;

    @Inject
    private ActuatorStateService states;

    @Inject
    private RoomRepository roomRepository;

    private final Jsonb jsonb = JsonbBuilder.create();

    @Override
    public void send(String roomId, String actuator, String action) {
        send(roomId, actuator, action, null);
    }

    @Override
    public void send(String roomId, String actuator, String action, String incidentId) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId manquant");
        }
        if (!ActuatorService.isValid(actuator, action)) {
            throw new IllegalArgumentException("Commande invalide : " + actuator + " " + action
                    + " (SIREN/VENTILATION : ON|OFF, GAS_VALVE/EMERGENCY_EXIT : OPEN|CLOSE)");
        }
        if (action.equals(states.getState(roomId, actuator))) {
            LOG.info("Commande ignoree, deja dans cet etat : " + roomId + " " + actuator + " " + action);
            return;
        }
        String topic = "smartbuilding/" + buildingOf(roomId) + "/" + roomId + "/cmd";
        String payload = jsonb.toJson(new ActuatorCommand(actuator, action, incidentId));
        mqtt.publish(topic, payload);
        LOG.info("Commande envoyee : " + roomId + " " + actuator + " " + action
                + (incidentId != null ? " (incident " + incidentId + ")" : " (manuelle)"));
    }

    /** Réactions automatiques du bâtiment, seulement à la création de l'incident. */
    public void onIncident(@Observes IncidentChanged event) {
        if (!"CREATED".equals(event.change())) {
            return;
        }
        Incident incident = event.incident();
        try {
            if (Detection.GAS_LEAK.equals(incident.getType())) {
                send(incident.getRoomId(), VENTILATION, "ON", incident.getId());
                send(incident.getRoomId(), GAS_VALVE, "CLOSE", incident.getId());
            } else if (Detection.FIRE.equals(incident.getType())) {
                send(incident.getRoomId(), SIREN, "ON", incident.getId());
                send(incident.getRoomId(), EMERGENCY_EXIT, "OPEN", incident.getId());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Reaction automatique impossible pour l'incident " + incident.getId(), e);
        }
    }

    private String buildingOf(String roomId) {
        try {
            return roomRepository.findById(roomId).map(Room::getBuildingId).orElse(DEFAULT_BUILDING);
        } catch (RuntimeException e) {
            return DEFAULT_BUILDING;
        }
    }

    @PreDestroy
    void close() {
        try {
            jsonb.close();
        } catch (Exception ignored) {
            // rien à faire
        }
    }
}
