package com.smartbuilding.mqtt;

/**
 * Commande publiée par le backend sur smartbuilding/{buildingId}/{roomId}/cmd (contrat 9) :
 * {"actuator": "VENTILATION", "action": "ON", "incidentId": "8c1f..."}
 * incidentId est null pour une commande manuelle.
 */
public record ActuatorCommand(String actuator, String action, String incidentId) {
}
