package com.smartbuilding.mqtt;

import com.smartbuilding.entities.SensorReading;
import com.smartbuilding.services.ReadingService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Écoute le broker MQTT dès le démarrage de l'application et enregistre chaque mesure reçue
 * via ReadingService.
 *
 * Adresse du broker : propriété système "mqtt.broker.url" ou variable d'environnement
 * MQTT_BROKER_URL, sinon tcp://localhost:1883.
 */
@ApplicationScoped
public class MqttListener implements MqttCallbackExtended {

    private static final Logger LOG = Logger.getLogger(MqttListener.class.getName());

    private static final String TOPIC = "smartbuilding/#";
    private static final int QOS = 1;
    private static final long RETRY_SECONDS = 5;

    @Inject
    ReadingService readingService;

    @Resource
    ManagedScheduledExecutorService scheduler;

    private final Jsonb jsonb = JsonbBuilder.create();
    private volatile MqttClient client;
    private volatile boolean stopping;

    /** Appelée une fois, quand l'application a démarré dans WildFly. */
    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        // Connexion en arrière-plan pour ne pas bloquer le déploiement si le broker est absent.
        scheduler.submit(this::connect);
    }

    private void connect() {
        if (stopping) {
            return;
        }
        String brokerUrl = brokerUrl();
        try {
            if (client == null) {
                String clientId = "sbe-backend-" + UUID.randomUUID().toString().substring(0, 8);
                client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
                client.setCallback(this);
            }
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);   // reconnexion auto après une coupure
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            client.connect(options);
            // L'abonnement est fait dans connectComplete(), aussi après chaque reconnexion.
        } catch (MqttException e) {
            LOG.warning("Broker MQTT injoignable (" + brokerUrl + ") : " + e.getMessage()
                    + ". Nouvel essai dans " + RETRY_SECONDS + " s.");
            scheduler.schedule(this::connect, RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void subscribe() {
        try {
            client.subscribe(TOPIC, QOS);
            LOG.info("Abonné au topic MQTT " + TOPIC);
        } catch (MqttException e) {
            LOG.log(Level.WARNING, "Abonnement à " + TOPIC + " impossible", e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        LOG.info((reconnect ? "Reconnecté" : "Connecté") + " au broker MQTT " + serverURI);
        // Sur un autre thread : un appel bloquant de Paho depuis un callback peut se bloquer.
        scheduler.submit(this::subscribe);
    }

    @Override
    public void connectionLost(Throwable cause) {
        LOG.warning("Connexion MQTT perdue : " + (cause != null ? cause.getMessage() : "raison inconnue")
                + ". Reconnexion automatique en cours.");
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Ne jamais laisser sortir d'exception ici : Paho couperait la connexion.
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            ReadingMessage msg = jsonb.fromJson(payload, ReadingMessage.class);
            if (msg == null || msg.sensorId() == null || msg.type() == null || msg.value() == null) {
                LOG.warning("Message ignoré (sensorId, type ou value manquant) sur " + topic + " : " + payload);
                return;
            }

            SensorReading reading = new SensorReading();
            reading.setSensorId(msg.sensorId());
            reading.setType(msg.type());
            reading.setValue(msg.value());
            reading.setUnit(msg.unit());
            reading.setTimestamp(msg.timestamp() != null && msg.timestamp() > 0
                    ? msg.timestamp()
                    : System.currentTimeMillis());

            SensorReading saved = readingService.save(reading);
            LOG.info("Mesure reçue : " + saved.getSensorId() + " " + saved.getType() + " = "
                    + saved.getValue() + " " + saved.getUnit() + " (id " + saved.getId() + ")");
        } catch (JsonbException e) {
            LOG.warning("JSON invalide sur " + topic + " : " + payload);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Mesure non enregistrée (topic " + topic + ")", e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Le backend ne publie rien pour l'instant.
    }

    @PreDestroy
    void stop() {
        stopping = true;
        try {
            if (client != null) {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            }
        } catch (MqttException e) {
            LOG.log(Level.FINE, "Erreur à la fermeture du client MQTT", e);
        }
        try {
            jsonb.close();
        } catch (Exception ignored) {
            // rien à faire
        }
    }

    private static String brokerUrl() {
        String value = System.getProperty("mqtt.broker.url");
        if (value == null || value.isBlank()) {
            value = System.getenv("MQTT_BROKER_URL");
        }
        return (value == null || value.isBlank()) ? "tcp://localhost:1883" : value;
    }
}
