package com.smartbuilding.detection;

/**
 * Résultat d'une règle de détection : une situation dangereuse dans une salle.
 *
 * @param type     GAS_LEAK ou FIRE
 * @param severity LOW, MEDIUM, HIGH ou CRITICAL (déjà relevée si la salle est occupée)
 * @param measured ce qui a été mesuré, en français, ex. "gaz à 1620 ppm sur 3 mesures consécutives (capteur gas-101)"
 */
public record Detection(
        String buildingId,
        String roomId,
        String sensorId,
        String type,
        String severity,
        boolean occupied,
        String measured,
        long timestamp) {

    public static final String GAS_LEAK = "GAS_LEAK";
    public static final String FIRE = "FIRE";
}
