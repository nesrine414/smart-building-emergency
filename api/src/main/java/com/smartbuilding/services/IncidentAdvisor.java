package com.smartbuilding.services;

import com.smartbuilding.detection.Detection;
import com.smartbuilding.entities.Room;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Localisation (LBS) et textes d'un incident, en Java pur (testable sans CDI) :
 * zone touchée, description et conseil d'évacuation (contrat 7).
 */
public final class IncidentAdvisor {

    private IncidentAdvisor() {}

    /** La salle d'origine en premier, puis les autres salles du même bâtiment et du même étage. */
    public static List<String> affectedRoomIds(String roomId, Room origin, List<Room> allRooms) {
        List<String> result = new ArrayList<>();
        result.add(roomId);
        if (origin == null || allRooms == null) {
            return result;
        }
        allRooms.stream()
                .filter(r -> !Objects.equals(r.getId(), roomId))
                .filter(r -> Objects.equals(r.getBuildingId(), origin.getBuildingId()))
                .filter(r -> r.getFloor() == origin.getFloor())
                .sorted(Comparator.comparing(Room::getId))
                .forEach(r -> result.add(r.getId()));
        return result;
    }

    /** "Gaz à 1620 ppm ... en salle 101, personnes présentes" */
    public static String description(Detection d, Room origin) {
        String measured = d.measured() == null || d.measured().isEmpty() ? "" :
                Character.toUpperCase(d.measured().charAt(0)) + d.measured().substring(1);
        return measured + " en " + roomLabel(d.roomId(), origin)
                + (d.occupied() ? ", personnes présentes" : "");
    }

    /** Conseil d'évacuation selon le type d'incident, l'étage et la sortie de la salle. */
    public static String recommendation(String type, String roomId, Room origin) {
        String where = roomLabel(roomId, origin) + (origin != null ? " (étage " + origin.getFloor() + ")" : "");
        String exit = origin != null && origin.getExit() != null && !origin.getExit().isBlank()
                ? origin.getExit()
                : "la sortie de secours la plus proche";
        if (Detection.GAS_LEAK.equals(type)) {
            return "Fuite de gaz en " + where + ". Quittez l'étage par la sortie conseillée : " + exit
                    + ". N'actionnez aucun interrupteur et n'allumez aucune flamme.";
        }
        if (Detection.FIRE.equals(type)) {
            return "Incendie en " + where + ". Évacuez l'étage par la sortie conseillée : " + exit
                    + ". N'utilisez pas l'ascenseur et fermez les portes derrière vous.";
        }
        return "Incident en " + where + ". Suivez les consignes de sécurité du bâtiment.";
    }

    private static String roomLabel(String roomId, Room origin) {
        if (origin != null && origin.getRoomNumber() != null && !origin.getRoomNumber().isBlank()) {
            return "salle " + origin.getRoomNumber();
        }
        return roomId != null && roomId.startsWith("room-") ? "salle " + roomId.substring(5) : String.valueOf(roomId);
    }
}
