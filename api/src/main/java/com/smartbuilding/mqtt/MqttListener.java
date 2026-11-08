package com.smartbuilding.mqtt;

import com.smartbuilding.entities.SensorReading;
import com.smartbuilding.services.ActuatorStateService;
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
 * Pont MQTT du backend.
 *
 * Reçoit sur smartbuilding/# :
 *   smartbuilding/{buildingId}/{roomId}/{TYPE}      -> mesure, enregistrée par ReadingService
 *                                                     (qui lance ReadingSaved : détection + WebSocket)
 *   smartbuilding/{buildingId}/{roomId}/actuator    -> confirmation d'un actionneur (ActuatorStateService)
 *   smartbuilding/{buildingId}/{roomId}/cmd         -> ignoré (ce sont nos propres commandes)
 *
 * Publie les commandes des actionneurs via publish() (utilisé par MqttActuatorService).
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

    @Inject
    ActuatorStateService actuatorStateService;

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

    // ---------- Connexion ----------

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
            LOG.info("Abonne au topic MQTT " + TOPIC);
        } catch (MqttException e) {
            LOG.log(Level.WARNING, "Abonnement a " + TOPIC + " impossible", e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        LOG.info((reconnect ? "Reconnecte" : "Connecte") + " au broker MQTT " + serverURI);
        // Sur un autre thread : un appel bloquant de Paho depuis un callback peut se bloquer.
        scheduler.submit(this::subscribe);
    }

    @Override
    public void connectionLost(Throwable cause) {
        LOG.warning("Connexion MQTT perdue : " + (cause != null ? cause.getMessage() : "raison inconnue")
                + ". Reconnexion automatique en cours.");
    }

    // ---------- Publication (commandes des actionneurs) ----------

    /**
     * Publie un message, sans bloquer l'appelant : la publication se fait sur un autre thread,
     * car publier en QoS 1 depuis le thread de réception de Paho peut le bloquer.
     */
    public void publish(String topic, String payload) {
        scheduler.submit(() -> {
            MqttClient c = client;
            if (c == null || !c.isConnected()) {
                LOG.warning("Commande non envoyee (broker deconnecte) : " + topic + " " + payload);
                return;
            }
            try {
                MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(QOS);
                message.setRetained(false);
                c.publish(topic, message);
            } catch (MqttException e) {
                LOG.log(Level.WARNING, "Publication impossible sur " + topic, e);
            }
        });
    }

    // ---------- Réception ----------

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Ne jamais laisser sortir d'exception ici : Paho couperait la connexion.
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            String[] parts = topic.split("/");
            if (parts.length != 4) {
                LOG.warning("Topic ignore (format attendu smartbuilding/{batiment}/{salle}/{type}) : " + topic);
                return;
            }
            String buildingId = parts[1];
            String roomId = parts[2];
            String last = parts[3];

            switch (last) {
                case "cmd" -> { /* nos propres commandes : rien à faire */ }
                case "actuator" -> handleActuator(buildingId, roomId, topic, payload);
                case "status" -> { /* état des appareils : prévu pour plus tard */ }
                default -> handleReading(buildingId, roomId, last, topic, payload);
            }
        } catch (JsonbException e) {
            LOG.warning("JSON invalide sur " + topic + " : " + payload);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Message non traite (topic " + topic + ")", e);
        }
    }

    private void handleReading(String buildingId, String roomId, String typeFromTopic, String topic, String payload) {
        ReadingMessage msg = jsonb.fromJson(payload, ReadingMessage.class);
        if (msg == null || msg.sensorId() == null || msg.value() == null) {
            LOG.warning("Mesure ignoree (sensorId ou value manquant) sur " + topic + " : " + payload);
            return;
        }

        SensorReading reading = new SensorReading();
        reading.setSensorId(msg.sensorId());
        reading.setType(msg.type() != null ? msg.type() : typeFromTopic);
        reading.setBuildingId(buildingId);
        reading.setRoomId(roomId);
        reading.setValue(msg.value());
        reading.setUnit(msg.unit());
        reading.setTimestamp(msg.timestamp() != null && msg.timestamp() > 0
                ? msg.timestamp()
                : System.currentTimeMillis());

        SensorReading saved = readingService.save(reading);
        LOG.info("Mesure recue : "+ saved.getSensorId() + " " + saved.getType() + " = "
                + saved.getValue() + " " + saved.getUnit() + " (" + roomId + ")");

        // ReadingService.save() lance ReadingSaved : la détection et le WebSocket le reçoivent.
    }

    private void handleActuator(String buildingId, String roomId, String topic, String payload) {
        ActuatorMessage msg = jsonb.fromJson(payload, ActuatorMessage.class);
        if (msg == null || msg.actuator() == null || msg.state() == null) {
            LOG.warning("Confirmation d'actionneur ignoree sur " + topic + " : " + payload);
            return;
        }
        LOG.info("Actionneur confirme : " + roomId + " " + msg.actuator() + " = " + msg.state());
        actuatorStateService.confirm(buildingId, roomId, msg.actuator(), msg.state(),
                msg.timestamp() != null ? msg.timestamp() : System.currentTimeMillis());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // rien à faire
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
            LOG.log(Level.FINE, "Erreur a la fermeture du client MQTT", e);
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
