package com.smartbuilding.websocket;

import com.smartbuilding.events.IncidentChanged;
import com.smartbuilding.events.ReadingSaved;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.websocket.Session;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.smartbuilding.events.ActuatorChanged;
@ApplicationScoped
public class EventBroadcaster {

    private static final Logger LOG = Logger.getLogger(EventBroadcaster.class.getName());

    // Sessions WebSocket actuellement connectées (thread-safe)
    private final Set<Session> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Jsonb jsonb = JsonbBuilder.create();

    public void addSession(Session session) {
        sessions.add(session);
        LOG.info("Nouvelle session WebSocket connectée : " + session.getId() + " (Total: " + sessions.size() + ")");
    }

    public void removeSession(Session session) {
        sessions.remove(session);
        LOG.info("Session WebSocket déconnectée : " + session.getId() + " (Total: " + sessions.size() + ")");
    }

    // 1. Écoute l'événement ReadingSaved et diffuse aux clients
    public void onReadingSaved(@Observes ReadingSaved event) {
        Map<String, Object> message = new HashMap<>();
        message.put("kind", "reading");
        message.put("data", event.reading());
        broadcast(message);
    }

    // 2. Écoute l'événement IncidentChanged et diffuse aux clients
    public void onIncidentChanged(@Observes IncidentChanged event) {
        Map<String, Object> message = new HashMap<>();
        message.put("kind", "incident");
        message.put("change", event.change());
        message.put("data", event.incident());
        broadcast(message);
    }
        // 3. Écoute l'événement ActuatorChanged et diffuse aux clients
    public void onActuatorChanged(@Observes ActuatorChanged event) {
        Map<String, Object> message = new HashMap<>();
        message.put("kind", "actuator");
        message.put("data", event.data());
        broadcast(message);
    }

    private void broadcast(Map<String, Object> payload) {
        if (sessions.isEmpty()) {
            return;
        }

        String json = jsonb.toJson(payload);
        for (Session session : sessions) {
            if (session.isOpen()) {
                session.getAsyncRemote().sendText(json, result -> {
                    if (!result.isOK()) {
                        LOG.log(Level.WARNING, "Erreur envoi WebSocket vers " + session.getId(), result.getException());
                    }
                });
            }
        }
    }
}