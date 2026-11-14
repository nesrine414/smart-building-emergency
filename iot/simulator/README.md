# Simulateur de capteurs MQTT

Publie des mesures simulées sur HiveMQ, sans matériel.

## Installation

```bash
cd iot/simulator
python -m venv .venv
.venv\Scripts\activate          # Windows (sous Linux/macOS : source .venv/bin/activate)
pip install -r requirements.txt
```

Le broker doit tourner :

```bash
docker run --name hivemq-ce -d -p 1883:1883 hivemq/hivemq-ce
```

## Utilisation

```bash
python simulator.py                          # mesures normales toutes les 2 s
python simulator.py --scenario gas-leak      # le gaz de la salle 101 monte de 300 à 2000 ppm
python simulator.py --scenario fire          # fumée et température montent dans la salle 101
python simulator.py --count 5 --interval 1   # 5 cycles, un par seconde, puis arrêt
```

Options : `--host`, `--port`, `--building` (défaut `bld-01`), `--rooms` (défaut `101,102,201`),
`--scenario`, `--interval`, `--count`, et depuis l'étape 3 `--tls`, `--cafile`, `--username`, `--password`.

Le scénario touche la **première** salle de `--rooms` : `--rooms 201,101,102 --scenario fire` simule
un incendie en salle 201.

## Broker sécurisé (étape 3)

Une fois le broker protégé (voir `infra/mqtt/README.md`), le simulateur se connecte avec le compte
`sbe-device`. Son mot de passe est lu automatiquement dans la variable `MQTT_DEVICE_PASSWORD`, ou dans
le fichier `.env` à la racine du dépôt : rien à taper.

```bash
python simulator.py --tls --scenario gas-leak     # port 8883, chiffré, certificat du broker vérifié
python simulator.py --scenario gas-leak           # port 1883 (transition), avec identifiants
```

Avec `--tls`, le port par défaut devient 8883 et le certificat du broker est vérifié avec la CA du
projet (`infra/mqtt/certs/ca.pem`, ou `--cafile`). En cas de refus, le simulateur s'arrête avec la
cause : mot de passe refusé, certificat refusé, ou port TLS utilisé sans `--tls`.

À chaque cycle, chaque salle envoie 5 mesures (`GAS`, `SMOKE`, `TEMPERATURE`, `HUMIDITY`, `MOTION`).
Dans un scénario, la valeur d'alerte est atteinte en 30 cycles (1 minute avec l'intervalle par défaut).

## Format des messages

Topic : `smartbuilding/{buildingId}/{roomId}/{type}`, par exemple `smartbuilding/bld-01/room-101/GAS`.

```json
{"sensorId": "gas-101", "type": "GAS", "value": 412.5, "unit": "ppm", "timestamp": 1762354800000}
```

`timestamp` est en millisecondes depuis 1970 (UTC).

## Actionneurs simulés (étape 2)

Le simulateur joue aussi le rôle des actionneurs. Il s'abonne à `smartbuilding/{bâtiment}/+/cmd`,
affiche chaque commande reçue (`>>> Salle 101 : ventilation ON (réaction automatique)`) et confirme
le nouvel état sur `smartbuilding/{bâtiment}/{salle}/actuator` :

```json
{"actuator": "VENTILATION", "state": "ON", "timestamp": 1762440013000}
```

Pendant `--scenario gas-leak`, quand la ventilation est allumée, le gaz monte encore 8 cycles
puis redescend de 80 ppm par cycle jusqu'à la normale.

Pour voir passer les messages : MQTT Explorer, connecté à `localhost:1883`, abonné à `smartbuilding/#`
(avec le broker sécurisé : voir `infra/mqtt/README.md`).
