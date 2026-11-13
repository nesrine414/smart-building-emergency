package com.smartbuilding.mqtt;

/**
 * Message JSON reçu par MQTT (contrat 3 du plan) :
 * {"sensorId": "gas-101", "type": "GAS", "value": 412.5, "unit": "ppm", "timestamp": 1762354800000}
 *
 * Types objets (Double, Long) pour détecter un champ absent.
 */
public record ReadingMessage(String sensorId, String type, Double value, String unit, Long timestamp) {
}
