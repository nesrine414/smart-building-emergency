package com.smartbuilding.ai;

import com.smartbuilding.entities.Room;
import com.smartbuilding.entities.SensorReading;
import com.smartbuilding.repositories.RoomRepository;
import com.smartbuilding.services.ReadingService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class BuildingTools {

    @Inject
    private ReadingService readingService;

    @Inject
    private RoomRepository roomRepository;

    // Tracker réel des outils exécutés par thread
    private final ThreadLocal<List<String>> executedToolsTracker = ThreadLocal.withInitial(ArrayList::new);

    public void startTracking() {
        executedToolsTracker.get().clear();
    }

    public List<String> getTrackedTools() {
        return Collections.unmodifiableList(new ArrayList<>(executedToolsTracker.get()));
    }

    public void stopTracking() {
        executedToolsTracker.remove();
    }

    private void recordToolCall(String toolName) {
        List<String> list = executedToolsTracker.get();
        if (!list.contains(toolName)) {
            list.add(toolName);
        }
    }

    /**
     * Outil 1 : Récupérer un résumé court des dernières mesures de la salle.
     */
    @Tool("Fournit les dernieres mesures de capteurs (gaz, fumee, temperature, humidite, mouvement) pour une salle donnee")
    public String getRecentReadings(@P("Identifiant de la salle, ex: room-101") String roomId) {
        recordToolCall("getRecentReadings");
        if (roomId == null || roomId.isBlank()) {
            return "Identifiant de salle non specifie.";
        }

        List<SensorReading> readings = readingService.findLatest(100, null);
        Map<String, String> latestPerType = new HashMap<>();

        for (SensorReading r : readings) {
            if (roomId.equalsIgnoreCase(r.getRoomId()) || (r.getSensorId() != null && r.getSensorId().contains(roomId.replace("room-", "")))) {
                if (!latestPerType.containsKey(r.getType())) {
                    latestPerType.put(r.getType(), r.getValue() + " " + (r.getUnit() != null ? r.getUnit() : ""));
                }
            }
        }

        if (latestPerType.isEmpty()) {
            return "Aucune mesure recente disponible pour la salle " + roomId;
        }

        return "Mesures salle " + roomId + " : " + latestPerType.toString();
    }

    /**
     * Outil 2 : Récupérer l'issue de secours conseillée pour cette salle.
     */
    @Tool("Fournit l'issue de secours et l'escalier d'evacuation conseille pour une salle donnee")
    public String getRoomEvacuationExit(@P("Identifiant de la salle, ex: room-101") String roomId) {
        recordToolCall("getRoomEvacuationExit");
        if (roomId == null || roomId.isBlank()) {
            return "Salle inconnue.";
        }
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isPresent()) {
            Room room = optRoom.get();
            return "Salle " + roomId + " (Etage " + room.getFloor() + ") -> Sortie de secours recommandee : " + room.getExit();
        }
        return "Information d'evacuation non trouvee pour la salle " + roomId;
    }

    /**
     * Outil 3 : Récupérer le score de risque prédit par le modèle ML de la Personne 1.
     */
    @Tool("Fournit le score de risque d'incendie calcule par le modele Machine Learning (entre 0 et 1) pour une salle")
    public String getRiskScore(@P("Identifiant de la salle, ex: room-101") String roomId) {
        recordToolCall("getRiskScore");
        List<SensorReading> readings = readingService.findLatest(50, null);
        for (SensorReading r : readings) {
            if ("RISK".equalsIgnoreCase(r.getType())) {
                if (roomId == null || roomId.equalsIgnoreCase(r.getRoomId()) || (r.getSensorId() != null && r.getSensorId().contains("101"))) {
                    return "Score de risque ML incendie pour " + roomId + " : " + r.getValue() + "/1.0";
                }
            }
        }
        return "Score de risque ML non disponible actuellement pour " + roomId + " (valeur normale estimee < 0.2)";
    }
}