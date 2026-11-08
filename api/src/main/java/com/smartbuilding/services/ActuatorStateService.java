package com.smartbuilding.services;

import com.smartbuilding.events.ActuatorChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dernier état CONFIRMÉ par l'appareil pour chaque actionneur de chaque salle (contrat 9).
 * En mémoire : suffisant pour la démo, l'appareil renvoie son état à chaque changement.
 *
 * Chaque nouvel état lance ActuatorChanged, que EventBroadcaster diffuse sur le WebSocket :
 * {"kind": "actuator", "data": {"buildingId", "roomId", "actuator", "state", "timestamp"}}
 */
@ApplicationScoped
public class ActuatorStateService {

    /** roomId -> (actuator -> state) */
    private final Map<String, Map<String, String>> states = new ConcurrentHashMap<>();

    @Inject
    Event<ActuatorChanged> actuatorChanged;

    /** Appelée par MqttListener à la réception d'une confirmation sur .../actuator. */
    public void confirm(String buildingId, String roomId, String actuator, String state, long timestamp) {
        Map<String, String> room = states.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
        String previous = room.put(actuator, state);
        if (state.equals(previous)) {
            return;  // rien de nouveau
        }
        Map<String, Object> data = new HashMap<>();
        data.put("buildingId", buildingId);
        data.put("roomId", roomId);
        data.put("actuator", actuator);
        data.put("state", state);
        data.put("timestamp", timestamp > 0 ? timestamp : System.currentTimeMillis());
        actuatorChanged.fire(new ActuatorChanged(data));
    }

    /** État confirmé, ou null si l'appareil n'a encore rien confirmé. */
    public String getState(String roomId, String actuator) {
        Map<String, String> room = states.get(roomId);
        return room == null ? null : room.get(actuator);
    }

    /** Tous les états confirmés d'une salle (copie). */
    public Map<String, String> getStates(String roomId) {
        return Map.copyOf(states.getOrDefault(roomId, Map.of()));
    }
}
