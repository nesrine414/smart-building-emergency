package com.smartbuilding.mqtt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;

/** Tests de la configuration MQTT du backend (contrat 5 de l'étape 3), sans broker. */
class MqttSettingsTest {

    private static MqttSettings settings(Map<String, String> env) {
        return MqttSettings.from(env, key -> null);
    }

    @Test
    void sansVariableGardeLeComportementDesEtapes1Et2() throws Exception {
        MqttSettings s = settings(Map.of());
        assertEquals("tcp://localhost:1883", s.brokerUrl());
        assertFalse(s.isTls());
        assertFalse(s.hasCredentials());
        assertNull(s.problem());
        MqttConnectOptions options = s.toConnectOptions();
        assertNull(options.getUserName());
        assertNull(options.getSocketFactory());
        assertTrue(options.isAutomaticReconnect());
    }

    @Test
    void identifiantsTransmisAPaho() throws Exception {
        MqttSettings s = settings(Map.of("MQTT_USERNAME", "sbe-backend", "MQTT_PASSWORD", "secret-123456"));
        MqttConnectOptions options = s.toConnectOptions();
        assertEquals("sbe-backend", options.getUserName());
        assertArrayEquals("secret-123456".toCharArray(), options.getPassword());
    }

    @Test
    void motDePasseJamaisDansLesLogs() {
        MqttSettings s = settings(Map.of("MQTT_USERNAME", "sbe-backend", "MQTT_PASSWORD", "secret-123456"));
        assertFalse(s.toString().contains("secret-123456"));
        assertTrue(s.toString().contains("sbe-backend"));
    }

    @Test
    void identifiantSansMotDePasseRefuse() {
        assertNotNull(settings(Map.of("MQTT_USERNAME", "sbe-backend")).problem());
    }

    @Test
    void urlInconnueRefusee() {
        assertNotNull(settings(Map.of("MQTT_BROKER_URL", "mqtt://localhost:1883")).problem());
    }

    @Test
    void truststoreManquantSignale() {
        Map<String, String> env = new HashMap<>();
        env.put("MQTT_BROKER_URL", "ssl://localhost:8883");
        env.put("MQTT_TRUSTSTORE", "/chemin/qui/n/existe/pas.jks");
        env.put("MQTT_TRUSTSTORE_PASSWORD", "changeit");
        assertNotNull(settings(env).problem());
    }

    @Test
    void tlsAvecTruststoreDuProjet() throws Exception {
        Path file = Files.createTempFile("truststore", ".jks");
        KeyStore empty = KeyStore.getInstance("JKS");
        empty.load(null, null);
        try (OutputStream out = Files.newOutputStream(file)) {
            empty.store(out, "changeit".toCharArray());
        }
        Map<String, String> env = new HashMap<>();
        env.put("MQTT_BROKER_URL", "ssl://localhost:8883");
        env.put("MQTT_TRUSTSTORE", file.toString());
        env.put("MQTT_TRUSTSTORE_PASSWORD", "changeit");
        MqttSettings s = settings(env);
        assertTrue(s.isTls());
        assertNull(s.problem());
        MqttConnectOptions options = s.toConnectOptions();
        assertNotNull(options.getSocketFactory());
        assertTrue(options.isHttpsHostnameVerificationEnabled());
        Files.delete(file);
    }

    @Test
    void proprieteSystemePrioritaire() {
        MqttSettings s = MqttSettings.from(Map.of("MQTT_BROKER_URL", "tcp://env:1883"),
                key -> key.equals("mqtt.broker.url") ? "tcp://propriete:1883" : null);
        assertEquals("tcp://propriete:1883", s.brokerUrl());
    }
}
