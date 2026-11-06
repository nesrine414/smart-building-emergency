# Personne 1 : tuyau MQTT (simulateur Python et réception dans WildFly)

Dézipper à la racine du projet `smart-building-emergency` (les dossiers correspondent).

## Fichiers

| Fichier | Nouveau ou modifié | Rôle |
| --- | --- | --- |
| `iot/simulator/simulator.py` | Nouveau | Publie les mesures simulées sur HiveMQ |
| `iot/simulator/requirements.txt` | Nouveau | Dépendance Python (`paho-mqtt` 2.x) |
| `iot/simulator/README.md` | Nouveau | Installation et options du simulateur |
| `api/pom.xml` | Modifié | Ajoute le client MQTT Eclipse Paho 1.2.5 |
| `api/src/main/java/com/smartbuilding/mqtt/MqttListener.java` | Nouveau | S'abonne à `smartbuilding/#` au démarrage et enregistre chaque mesure |
| `api/src/main/java/com/smartbuilding/mqtt/ReadingMessage.java` | Nouveau | Forme du message JSON reçu |
| `core/src/main/java/com/smartbuilding/entities/SensorReading.java` | Modifié | Ajoute le champ `type` |
| `core/src/main/java/com/smartbuilding/services/ReadingService.java` | Nouveau | Version provisoire en mémoire (contrat 5) |

Les deux fichiers de `core` relèvent de la personne 2 (tâche du jour 1). Si elle les a déjà poussés,
gardez sa version : `MqttListener` n'utilise que `ReadingService.save()` et `SensorReading.setType()`.

## Tester

1. `docker run --name hivemq-ce -d -p 1883:1883 hivemq/hivemq-ce`
2. Démarrer WildFly, puis à la racine : `mvn clean install` et `mvn -pl api wildfly:deploy`
3. Dans les logs de WildFly : `Connecté au broker MQTT tcp://localhost:1883` puis `Abonné au topic MQTT smartbuilding/#`
4. `cd iot/simulator`, `pip install -r requirements.txt`, puis `python simulator.py --scenario gas-leak`
5. Dans les logs de WildFly : `Mesure reçue : gas-101 GAS = 412.5 ppm (id ...)`

Si le broker n'est pas lancé, WildFly démarre quand même et réessaie toutes les 5 secondes.
Autre adresse de broker : variable d'environnement `MQTT_BROKER_URL` (par exemple `tcp://192.168.1.20:1883`).
