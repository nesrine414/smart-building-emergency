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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class BuildingTools {

    @Inject
    private ReadingService readingService;

    @Inject
    private RoomRepository roomRepository;

    private final ThreadLocal<List<String>> executedToolsTracker =
            ThreadLocal.withInitial(ArrayList::new);

    public void startTracking() {
        executedToolsTracker.get().clear();
    }

    public List<String> getTrackedTools() {
        return Collections.unmodifiableList(
                new ArrayList<>(executedToolsTracker.get()));
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
     * Retourne les mesures reelles, en conservant type, valeur et unite.
     * GAS ne permet pas de determiner une espece chimique.
     */
    @Tool("Retourne les dernieres mesures reelles de la salle exacte. " +
          "Chaque mesure contient son type technique exact, sa valeur, son unite et son capteur. " +
          "Ne transforme jamais GAS en CO, CO2 ou methane.")
    public String getRecentReadings(
            @P("Identifiant exact de la salle, par exemple room-102") String roomId) {

        recordToolCall("getRecentReadings");

        if (roomId == null || roomId.isBlank()) {
            return "Identifiant de salle non specifie. Aucune mesure retournee.";
        }

        List<SensorReading> readings =
                readingService.findLatestByRoomId(roomId, 100);

        // Conserver la mesure la plus recente pour chaque type et chaque capteur.
        Map<String, SensorReading> latestPerSensor = new LinkedHashMap<>();

        for (SensorReading reading : readings) {
            // Double verification : aucune correspondance partielle de salle.
            if (!roomId.equals(reading.getRoomId())) {
                continue;
            }

            String type = reading.getType() != null
                    ? reading.getType()
                    : "TYPE_NON_RENSEIGNE";

            String sensorId = reading.getSensorId() != null
                    ? reading.getSensorId()
                    : "CAPTEUR_NON_RENSEIGNE";

            String key = type + "|" + sensorId;

            latestPerSensor.putIfAbsent(key, reading);
        }

        if (latestPerSensor.isEmpty()) {
            return "Aucune mesure enregistree pour la salle exacte "
                    + roomId + ". Ne pas supposer de valeur.";
        }

        List<String> lines = new ArrayList<>();

        for (SensorReading reading : latestPerSensor.values()) {
            String type = reading.getType() != null
                    ? reading.getType()
                    : "TYPE_NON_RENSEIGNE";

            String unit = reading.getUnit() != null
                    && !reading.getUnit().isBlank()
                    ? reading.getUnit()
                    : "UNITE_NON_RENSEIGNEE";

            String sensorId = reading.getSensorId() != null
                    ? reading.getSensorId()
                    : "CAPTEUR_NON_RENSEIGNE";

            lines.add(
                    "type=" + type
                    + " | valeur=" + Double.toString(reading.getValue())
                    + " | unite=" + unit
                    + " | capteur=" + sensorId
                    + " | timestamp=" + reading.getTimestamp()
            );
        }

        return "MESURES REELLES DE LA SALLE " + roomId + " :\n"
                + String.join("\n", lines)
                + "\nIMPORTANT : GAS est uniquement le type technique enregistre. "
                + "Il ne permet pas de deduire CO, CO2 ou methane. "
                + "SMOKE ne doit pas etre transforme en une autre substance. "
                + "Aucune mesure ne doit etre declaree normale ou sure "
                + "sans reference de seuil appropriee.";
    }

    /**
     * Retourne uniquement la sortie enregistree pour la salle.
     */
    @Tool("Fournit la sortie de secours enregistree pour une salle. " +
          "Ne garantit pas que le passage est libre en temps reel.")
    public String getRoomEvacuationExit(
            @P("Identifiant exact de la salle, par exemple room-102") String roomId) {

        recordToolCall("getRoomEvacuationExit");

        if (roomId == null || roomId.isBlank()) {
            return "Salle inconnue.";
        }

        Optional<Room> optRoom = roomRepository.findById(roomId);

        if (optRoom.isPresent()) {
            Room room = optRoom.get();
            String exit = room.getExit();

            if (exit == null || exit.isBlank()) {
                return "Aucune sortie d'evacuation enregistree pour "
                        + roomId + ". Consulter le plan officiel sur place.";
            }

            return "Salle " + roomId
                    + " | Etage=" + room.getFloor()
                    + " | Sortie enregistree=" + exit
                    + " | Disponibilite reelle du passage non verifiee.";
        }

        return "Information d'evacuation non trouvee pour " + roomId;
    }

    /**
     * Retourne le score RISK de la salle exacte, sans autre salle de substitution.
     */
    @Tool("Fournit le score de risque ML RISK de la salle exacte. " +
          "Ne retourne jamais le score d'une autre salle.")
    public String getRiskScore(
            @P("Identifiant exact de la salle, par exemple room-102") String roomId) {

        recordToolCall("getRiskScore");

        if (roomId == null || roomId.isBlank()) {
            return "Score ML indisponible : identifiant de salle manquant.";
        }

        List<SensorReading> readings =
                readingService.findLatestByRoomId(roomId, 100);

        for (SensorReading reading : readings) {
            if (roomId.equals(reading.getRoomId())
                    && "RISK".equalsIgnoreCase(reading.getType())) {

                String unit = reading.getUnit() != null
                        ? reading.getUnit()
                        : "unite non renseignee";

                return "Score ML RISK de " + roomId
                        + " : valeur=" + reading.getValue()
                        + " | unite=" + unit
                        + " | capteur=" + reading.getSensorId()
                        + " | timestamp=" + reading.getTimestamp()
                        + ". Ce score est une aide a la decision.";
            }
        }

        return "Score de risque ML indisponible pour "
                + roomId + ". Aucune valeur ne peut etre supposee.";
    }
}