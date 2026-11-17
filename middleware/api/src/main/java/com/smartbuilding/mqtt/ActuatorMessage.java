package com.smartbuilding.mqtt;

/**
 * Confirmation envoyée par l'appareil sur smartbuilding/{buildingId}/{roomId}/actuator (contrat 9) :
 * {"actuator": "VENTILATION", "state": "ON", "timestamp": 1762440013000}
 */
public record ActuatorMessage(String actuator, String state, Long timestamp) {
}
