package com.smartbuilding.mqtt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;

/**
 * Paramètres de connexion du backend au broker MQTT (contrat 5 de l'étape 3).
 *
 * <p>Lus dans les variables d'environnement (ou, à défaut, dans les propriétés système
 * entre parenthèses) :</p>
 * <ul>
 *   <li>{@code MQTT_BROKER_URL} ({@code mqtt.broker.url}) : {@code ssl://localhost:8883} en TLS,
 *       {@code tcp://localhost:1883} sans chiffrement. Défaut : {@code tcp://localhost:1883}.</li>
 *   <li>{@code MQTT_USERNAME} ({@code mqtt.username}) et {@code MQTT_PASSWORD} ({@code mqtt.password}) :
 *       compte {@code sbe-backend}. Sans eux, connexion anonyme (refusée une fois l'extension RBAC active).</li>
 *   <li>{@code MQTT_TRUSTSTORE} ({@code mqtt.truststore}) et {@code MQTT_TRUSTSTORE_PASSWORD}
 *       ({@code mqtt.truststore.password}) : truststore JKS qui contient la CA locale du projet,
 *       généré par infra/mqtt/gen-certs. Utilisé seulement avec {@code ssl://}.</li>
 * </ul>
 *
 * <p>Classe sans CDI, pour pouvoir la tester seule ({@code MqttSettingsTest}). Le mot de passe
 * n'apparaît jamais dans {@link #toString()}.</p>
 */
public final class MqttSettings {

    static final String DEFAULT_URL = "tcp://localhost:1883";

    private final String brokerUrl;
    private final String username;
    private final char[] password;
    private final String truststorePath;
    private final char[] truststorePassword;

    MqttSettings(String brokerUrl, String username, char[] password,
                 String truststorePath, char[] truststorePassword) {
        this.brokerUrl = isBlank(brokerUrl) ? DEFAULT_URL : brokerUrl.trim();
        this.username = isBlank(username) ? null : username.trim();
        this.password = password;
        this.truststorePath = isBlank(truststorePath) ? null : truststorePath.trim();
        this.truststorePassword = truststorePassword;
    }

    /** Lit la configuration de l'environnement réel du processus (WildFly). */
    public static MqttSettings fromEnvironment() {
        return from(System.getenv(), System::getProperty);
    }

    /** Variante testable : {@code env} pour les variables, {@code properties} pour les propriétés système. */
    static MqttSettings from(Map<String, String> env, Function<String, String> properties) {
        String password = read(env, properties, "MQTT_PASSWORD", "mqtt.password");
        String truststorePassword = read(env, properties, "MQTT_TRUSTSTORE_PASSWORD", "mqtt.truststore.password");
        return new MqttSettings(
                read(env, properties, "MQTT_BROKER_URL", "mqtt.broker.url"),
                read(env, properties, "MQTT_USERNAME", "mqtt.username"),
                password == null ? null : password.toCharArray(),
                read(env, properties, "MQTT_TRUSTSTORE", "mqtt.truststore"),
                truststorePassword == null ? null : truststorePassword.toCharArray());
    }

    /** Propriété système d'abord (comme avant pour mqtt.broker.url), puis variable d'environnement. */
    private static String read(Map<String, String> env, Function<String, String> properties,
                               String envKey, String propertyKey) {
        String value = properties.apply(propertyKey);
        return isBlank(value) ? env.get(envKey) : value;
    }

    public String brokerUrl() {
        return brokerUrl;
    }

    public boolean isTls() {
        return brokerUrl.startsWith("ssl://");
    }

    public boolean hasCredentials() {
        return username != null;
    }

    /**
     * Vérifie la cohérence de la configuration. Renvoie un message d'erreur clair, ou {@code null}
     * si tout va bien. Appelé avant la première connexion pour ne pas réessayer en boucle une
     * configuration qui ne peut pas marcher.
     */
    public String problem() {
        if (!brokerUrl.startsWith("tcp://") && !brokerUrl.startsWith("ssl://")) {
            return "MQTT_BROKER_URL doit commencer par tcp:// ou ssl:// (valeur : " + brokerUrl + ")";
        }
        if (username != null && (password == null || password.length == 0)) {
            return "MQTT_USERNAME est defini mais MQTT_PASSWORD est vide";
        }
        if (isTls() && truststorePath != null) {
            if (!Files.isReadable(Path.of(truststorePath))) {
                return "Truststore MQTT illisible : " + truststorePath
                        + " (generer les certificats avec infra/mqtt/gen-certs)";
            }
            if (truststorePassword == null || truststorePassword.length == 0) {
                return "MQTT_TRUSTSTORE est defini mais MQTT_TRUSTSTORE_PASSWORD est vide";
            }
        }
        return null;
    }

    /** Options Paho : reconnexion automatique, identifiants et, en ssl://, le truststore du projet. */
    public MqttConnectOptions toConnectOptions() throws GeneralSecurityException, IOException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        if (username != null) {
            options.setUserName(username);
            options.setPassword(password.clone());
        }
        if (isTls()) {
            // Vérifie que le nom du broker (localhost) figure bien dans son certificat.
            options.setHttpsHostnameVerificationEnabled(true);
            if (truststorePath != null) {
                options.setSocketFactory(socketFactory());
            }
            // Sans truststore, Paho utilise les autorités du JDK : la CA locale n'y est pas,
            // la connexion échouera avec "PKIX path building failed".
        }
        return options;
    }

    /** Fabrique de sockets TLS qui ne fait confiance qu'aux certificats du truststore du projet. */
    SSLSocketFactory socketFactory() throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(Path.of(truststorePath))) {
            trustStore.load(in, truststorePassword);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    @Override
    public String toString() {
        return brokerUrl
                + (username != null ? " (compte " + username + ")" : " (anonyme)")
                + (isTls() ? (truststorePath != null ? ", truststore " + truststorePath : ", truststore du JDK") : "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
