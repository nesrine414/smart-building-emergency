# Sécurité MQTT : identifiants, droits par topic et TLS (étape 3, contrat 5)

HiveMQ Community Edition n'a ni mot de passe ni contrôle d'accès intégré : son image Docker accepte
tous les clients grâce à une extension « allow-all ». On la remplace par l'extension officielle
**File RBAC** (comptes, mots de passe hachés, droits par topic) et on ajoute un port **TLS 8883**.

| Compte | Utilisé par | Peut publier | Peut s'abonner |
| --- | --- | --- | --- |
| `sbe-backend` | WildFly (`MqttListener`) | `smartbuilding/+/+/cmd` | `smartbuilding/#` |
| `sbe-device` | simulateur, ESP32 | mesures (`GAS`, `SMOKE`, `TEMPERATURE`, `HUMIDITY`, `MOTION`) et `.../actuator` | `smartbuilding/+/+/cmd` |
| `sbe-ml` | `iot/mlops/infer.py` | `smartbuilding/+/+/RISK` | les 5 types de mesures |

En plus : aucun message `retained` n'est accepté (un appareil ne peut pas laisser une fausse valeur
sur le broker), et un client qui publie hors de ses droits reçoit un refus puis est **déconnecté**
par HiveMQ.

## Fichiers

| Fichier | Rôle | Sur Git ? |
| --- | --- | --- |
| `hivemq/config.xml` | Ports 1883 (transition) et 8883 TLS | oui |
| `hivemq/config-tls-only.xml` | Configuration finale : 8883 seulement | oui |
| `rbac/config.xml` | Réglages de l'extension (mots de passe hachés, relecture toutes les 60 s) | oui |
| `gen_credentials.py` | Crée le `.env` (`--init-env`) puis `rbac/credentials.xml` (comptes, hachés, droits) | oui |
| `install-rbac.ps1` / `.sh` | Télécharge l'extension 4.6.19 et vérifie son empreinte SHA-256 | oui |
| `gen-certs.ps1` / `.sh` | CA locale + certificat du broker + truststore Java, avec `keytool` | oui |
| `backend-env.ps1` / `.sh` | Prépare les variables MQTT de WildFly dans la console | oui |
| `check_mqtt_security.py` | Contrôle automatique de tout le contrat 5 (26 vérifications) | oui |
| `rbac/credentials.xml`, `certs/`, `extensions/`, `../../.env` | Générés sur chaque PC | **non** |
| `../../docker-compose.secure.yml` | Ajoute à HiveMQ le port 8883, l'extension et les certificats | oui |

## Mise en place (Windows, PowerShell, depuis la racine du dépôt)

Prérequis : Docker Desktop, un JDK 17+ (pour `keytool`, déjà installé pour WildFly), Python avec
`paho-mqtt` (`pip install -r iot/simulator/requirements.txt`).

```powershell
# 1. Secrets de l'équipe : crée .env et remplit les mots de passe vides (aléatoires, 24 caractères)
python infra\mqtt\gen_credentials.py --init-env
#    -> partager ce .env avec l'équipe par un canal privé (jamais Git, jamais le groupe public)

# 2. Extension File RBAC
powershell -ExecutionPolicy Bypass -File infra\mqtt\install-rbac.ps1

# 3. Certificats TLS
powershell -ExecutionPolicy Bypass -File infra\mqtt\gen-certs.ps1

# 4. Comptes et droits (à relancer après chaque changement de mot de passe dans .env)
python infra\mqtt\gen_credentials.py

# 5. Broker sécurisé
docker compose -f docker-compose.yml -f docker-compose.secure.yml up -d --force-recreate hivemq
docker logs smart-building-hivemq
```

Dans les logs de HiveMQ, il faut voir :

```
Disabling allow all extension
Extension "HiveMQ File Role Based Access Control Extension" version 4.6.19 started successfully.
Started TCP Listener on address 0.0.0.0 and on port 1883.
Started TCP Listener with TLS on address 0.0.0.0 and on port 8883.
```

```powershell
# 6. Contrôle automatique : tout doit être OK
python infra\mqtt\check_mqtt_security.py
```

Sous Linux/macOS : mêmes étapes avec `bash infra/mqtt/install-rbac.sh` et `bash infra/mqtt/gen-certs.sh`.

## Brancher les clients

**Backend (WildFly)** : dans la console qui démarre WildFly,

```powershell
. .\infra\mqtt\backend-env.ps1          # le point au début est obligatoire
& "$env:WILDFLY_HOME\bin\standalone.bat"
```

Le script définit `MQTT_BROKER_URL=ssl://localhost:8883`, `MQTT_USERNAME=sbe-backend`,
`MQTT_PASSWORD`, `MQTT_TRUSTSTORE` (chemin absolu de `certs/truststore.jks`) et
`MQTT_TRUSTSTORE_PASSWORD`. Logs attendus :

```
Broker MQTT : ssl://localhost:8883 (compte sbe-backend), truststore C:\...\certs\truststore.jks
Connecte au broker MQTT ssl://localhost:8883
Abonne au topic MQTT smartbuilding/#
```

Sans ces variables, `MqttListener` garde le comportement des étapes 1 et 2 (`tcp://localhost:1883`
anonyme) : utile tant que le broker n'est pas encore sécurisé.

**Simulateur** : `python iot\simulator\simulator.py --tls --scenario gas-leak`
(compte `sbe-device`, mot de passe lu dans `.env`, CA `infra/mqtt/certs/ca.pem`).

**Score ML** : `cd iot\mlops ; python infer.py --tls` (compte `sbe-ml`).

**MQTT Explorer** : hôte `localhost`, port `8883`, « Encryption (tls) » activé, « Validate certificate »
activé avec le certificat `infra/mqtt/certs/ca.pem` (Advanced > Certificates > Server Certificate (CA)),
utilisateur `sbe-backend` et son mot de passe du `.env`.

## Fermer le port 1883 (fin de la transition)

Quand le backend, le simulateur et `infer.py` passent tous par le 8883 :

```powershell
# dans .env : HIVEMQ_CONFIG=config-tls-only.xml
docker compose -f docker-compose.yml -f docker-compose.secure.yml up -d --force-recreate hivemq
python infra\mqtt\check_mqtt_security.py      # "port 1883 fermé : seul le TLS est accepté"
```

## ESP32 (étape matérielle suivante)

Tout est prêt côté broker : compte `sbe-device`, certificat de la CA `certs/ca.pem` à copier dans le
programme (`WiFiClientSecure::setCACert`), port 8883. L'ESP32 se connecte par l'IP du PC sur le
Wi-Fi : mettre cette IP dans `MQTT_BROKER_LAN_IP` (`.env`), puis relancer `gen-certs.ps1 -Force` et
recréer HiveMQ, pour qu'elle figure dans le certificat du broker.

## En cas de problème

| Symptôme | Cause probable | Solution |
| --- | --- | --- |
| `Bad user name or password` / `Not authorized` | Client sans identifiant, ou mot de passe différent de celui qui a servi à générer `credentials.xml` | Même `.env` pour tous ; relancer `gen_credentials.py` (HiveMQ relit le fichier en 60 s, ou recréer le conteneur) |
| `PKIX path building failed` (Java) | `MQTT_TRUSTSTORE` absent : Java ne connaît pas notre CA | Lancer WildFly depuis une console où `backend-env.ps1` a été chargé |
| `No subject alternative names matching ...` / `certificate verify failed` | Le client utilise un nom absent du certificat (ex. `127.0.0.2`, nom du PC) | Utiliser `localhost`, ou ajouter l'IP dans `MQTT_BROKER_LAN_IP` et régénérer les certificats |
| `docker compose` refuse de démarrer : `required variable MQTT_KEYSTORE_PASSWORD is missing a value` | `.env` absent ou incomplet | `gen_credentials.py --init-env` |
| Le conteneur HiveMQ redémarre en boucle ; log `Not able to open or read KeyStore` ou `Cannot find KeyStore` | Mot de passe du keystore changé dans `.env` après `gen-certs`, ou certificats pas encore générés | `gen-certs.ps1 -Force`, puis recréer le conteneur |
| Tous les clients refusés, même avec le bon mot de passe ; log `Exception thrown at extension start` | `credentials.xml` absent : Docker a créé un **dossier** à sa place | Supprimer ce dossier, relancer `gen_credentials.py`, recréer le conteneur |
| Le client publie mais rien n'arrive, puis se reconnecte | Publication hors des droits du compte (ex. une commande avec `sbe-device`) | Respecter le tableau du contrat 5 |

Dans tous ces cas, le broker reste fermé : il refuse les clients ou ne démarre pas, il ne repasse
jamais en mode ouvert.

## Vérifié avant livraison

Testé avec un vrai HiveMQ CE 2026.5, l'extension File RBAC 4.6.19 et les fichiers de ce dossier
(hors Docker, mêmes fichiers et mêmes chemins que dans le conteneur) : `check_mqtt_security.py`
réussit tous ses contrôles avec `config.xml`, puis avec `config-tls-only.xml` (port 1883 fermé) ;
le client Java Paho 1.2.5 configuré comme `MqttListener` se connecte en TLS, et les erreurs
(mot de passe faux, truststore absent, nom hors certificat) donnent les messages ci-dessus ;
le haché de `gen_credentials.py` est identique à celui de l'outil officiel de l'extension ; les
pannes du tableau ci-dessus ont été provoquées exprès pour relever les messages exacts.
