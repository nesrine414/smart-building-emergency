package com.smartbuilding.detection;

import com.smartbuilding.entities.SensorReading;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Règles de détection du contrat 2 (plan de l'étape 2), en Java pur : pas de CDI ni de base de données,
 * ce qui permet de le tester avec JUnit.
 *
 * <ul>
 *   <li>Fuite de gaz : GAS au-dessus de 1000 ppm sur 3 mesures consécutives du même capteur ;
 *       MEDIUM, puis HIGH au-dessus de 1500 ppm.</li>
 *   <li>Incendie : SMOKE au-dessus de 300 ppm ET TEMPERATURE au-dessus de 45 °C dans la même salle,
 *       les deux mesurées dans les 30 dernières secondes ; HIGH.</li>
 *   <li>Salle occupée : un MOTION à 1 dans les 60 dernières secondes ; gravité relevée d'un niveau.</li>
 * </ul>
 *
 * Le temps utilisé est celui des mesures (timestamp), pas l'horloge du serveur.
 */
public class DetectionEngine {

    // ---------- Seuils (contrat 2) ----------
    public static final double GAS_THRESHOLD_PPM = 1000;
    public static final double GAS_HIGH_PPM = 1500;
    public static final int GAS_CONSECUTIVE = 3;
    public static final double SMOKE_THRESHOLD_PPM = 300;
    public static final double FIRE_TEMPERATURE_C = 45;
    public static final long FIRE_WINDOW_MS = 30_000;
    public static final long OCCUPANCY_WINDOW_MS = 60_000;

    private static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    /** Dernière valeur d'un capteur dans une salle. */
    private record Last(double value, long timestamp) {}

    private final Map<String, Integer> gasCountBySensor = new HashMap<>();
    private final Map<String, Last> lastSmokeByRoom = new HashMap<>();
    private final Map<String, Last> lastTemperatureByRoom = new HashMap<>();
    private final Map<String, Long> lastMotionByRoom = new HashMap<>();

    /**
     * Analyse une mesure. Renvoie une détection si une règle se déclenche,
     * sinon Optional.empty(). La salle doit être connue (roomId non nul).
     */
    public synchronized Optional<Detection> analyze(SensorReading r) {
        if (r == null || r.getType() == null || r.getRoomId() == null) {
            return Optional.empty();
        }
        String room = r.getRoomId();
        long now = r.getTimestamp();

        switch (r.getType()) {
            case "MOTION" -> {
                if (r.getValue() >= 0.5) {
                    lastMotionByRoom.put(room, now);
                }
                return Optional.empty();
            }
            case "GAS" -> {
                return analyzeGas(r, room, now);
            }
            case "SMOKE" -> {
                lastSmokeByRoom.put(room, new Last(r.getValue(), now));
                return analyzeFire(r, room, now);
            }
            case "TEMPERATURE" -> {
                lastTemperatureByRoom.put(room, new Last(r.getValue(), now));
                return analyzeFire(r, room, now);
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /** Vrai si un mouvement a été vu dans la salle dans les 60 s précédant 'now'. */
    public synchronized boolean isOccupied(String roomId, long now) {
        Long last = lastMotionByRoom.get(roomId);
        return last != null && now - last <= OCCUPANCY_WINDOW_MS && now >= last;
    }

    private Optional<Detection> analyzeGas(SensorReading r, String room, long now) {
        String sensor = r.getSensorId();
        if (r.getValue() <= GAS_THRESHOLD_PPM) {
            gasCountBySensor.remove(sensor);  // la série consécutive est cassée
            return Optional.empty();
        }
        int count = gasCountBySensor.merge(sensor, 1, Integer::sum);
        if (count < GAS_CONSECUTIVE) {
            return Optional.empty();
        }
        String base = r.getValue() > GAS_HIGH_PPM ? "HIGH" : "MEDIUM";
        boolean occupied = isOccupied(room, now);
        String measured = String.format(Locale.FRANCE,
                "gaz à %.0f ppm sur %d mesures consécutives (capteur %s)",
                r.getValue(), Math.min(count, 99), sensor);
        return Optional.of(new Detection(r.getBuildingId(), room, sensor, Detection.GAS_LEAK,
                occupied ? raise(base) : base, occupied, measured, now));
    }

    private Optional<Detection> analyzeFire(SensorReading r, String room, long now) {
        Last smoke = lastSmokeByRoom.get(room);
        Last temp = lastTemperatureByRoom.get(room);
        if (smoke == null || temp == null) {
            return Optional.empty();
        }
        boolean recent = now - smoke.timestamp() <= FIRE_WINDOW_MS && now - temp.timestamp() <= FIRE_WINDOW_MS;
        if (!recent || smoke.value() <= SMOKE_THRESHOLD_PPM || temp.value() <= FIRE_TEMPERATURE_C) {
            return Optional.empty();
        }
        boolean occupied = isOccupied(room, now);
        String measured = String.format(Locale.FRANCE,
                "fumée à %.0f ppm et température à %.1f °C", smoke.value(), temp.value());
        return Optional.of(new Detection(r.getBuildingId(), room, r.getSensorId(), Detection.FIRE,
                occupied ? raise("HIGH") : "HIGH", occupied, measured, now));
    }

    /** Monte la gravité d'un niveau (CRITICAL reste CRITICAL). */
    public static String raise(String severity) {
        int i = SEVERITIES.indexOf(severity);
        return i < 0 ? severity : SEVERITIES.get(Math.min(i + 1, SEVERITIES.size() - 1));
    }

    /** Compare deux gravités : positif si a est plus grave que b. */
    public static int compareSeverity(String a, String b) {
        return Integer.compare(SEVERITIES.indexOf(a), SEVERITIES.indexOf(b));
    }
}
