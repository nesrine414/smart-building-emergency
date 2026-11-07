package com.smartbuilding.services;

import com.smartbuilding.events.ActuatorChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class MqttActuatorService implements ActuatorService {

    private static final Logger LOG = Logger.getLogger(MqttActuatorService.class.getName());

    @Inject
    private Event<ActuatorChanged> actuatorChangedEvent;

    @Override
    public void send(String roomId, String actuator, String action) {
        LOG.info("Commande actionneur envoyée : Salle=" + roomId + ", Actionneur=" + actuator + ", Action=" + action);

        // Diffuser sur le WebSocket pour informer les navigateurs connectés
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("actuator", actuator);
        data.put("state", action);
        data.put("timestamp", System.currentTimeMillis());

        actuatorChangedEvent.fire(new ActuatorChanged(data));
    }
}