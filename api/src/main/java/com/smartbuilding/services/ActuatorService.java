package com.smartbuilding.services;

/**
 * Envoi d'une commande à un actionneur (contrat 9).
 * Implémentation : MqttActuatorService (module api), qui publie sur
 * smartbuilding/{buildingId}/{roomId}/cmd.
 */
public interface ActuatorService {

    String SIREN = "SIREN";
    String VENTILATION = "VENTILATION";
    String GAS_VALVE = "GAS_VALVE";
    String EMERGENCY_EXIT = "EMERGENCY_EXIT";

    /**
     * Commande manuelle (POST /api/rooms/{roomId}/actuators).
     *
     * @throws IllegalArgumentException si l'actionneur ou l'action n'existe pas
     */
    void send(String roomId, String actuator, String action);

    /** Commande liée à un incident (réaction automatique). Par défaut : comme une commande manuelle. */
    default void send(String roomId, String actuator, String action, String incidentId) {
        send(roomId, actuator, action);
    }

    /** Vrai si l'action est valide pour cet actionneur (ON/OFF pour les relais, OPEN/CLOSE pour les servos). */
    static boolean isValid(String actuator, String action) {
        if (actuator == null || action == null) {
            return false;
        }
        return switch (actuator) {
            case SIREN, VENTILATION -> action.equals("ON") || action.equals("OFF");
            case GAS_VALVE, EMERGENCY_EXIT -> action.equals("OPEN") || action.equals("CLOSE");
            default -> false;
        };
    }
}
