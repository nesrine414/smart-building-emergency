# 🏢 Smart Building Emergency System (SBE)
> **Module :** Cloud of Things (COT) · SUP'COM · Année 2026/2027  
> Système IoT distribué de surveillance d'urgence (incendies, fuites de gaz) en temps réel avec persistance NoSQL, communication événementielle et standard W3C Web of Things (WoT).

---

## 📌 1. Vue d'ensemble de l'Architecture

Le système interconnecte une chaîne complète de bout en bout :

```text
[Simulateur IoT Python]
        │
        ▼ (MQTT - Port 1883)
[HiveMQ Broker (Docker)]
        │
        ▼ (MqttListener)
[Serveur WildFly 42 (Port 8081 / cot.xml)]
 ├── Jakarta Data / JNoSQL 1.1.6 ──► [MongoDB (Port 27017)]
 ├── CDI Event Bus (ReadingSaved, IncidentChanged, ActuatorChanged)
 ├── Jakarta REST (/api/readings, /api/incidents, /api/buildings, /api/push, /api/td)
 └── Jakarta WebSocket (/api/ws/events)
        │
        ▼ (HTTP REST & WebSockets)
[Frontends Angular]
 ├── Front Office (PWA Client - Occupants)
 └── Back Office (PWA Admin - Gestionnaires d'urgence)
```

---

## 👥 2. Répartition du Travail d'Équipe

| Rôle | Périmètre technique |
| :--- | :--- |
| **Personne 1 (IoT & Edge)** | Simulateur Python multi-scénarios (`gas-leak`, `fire`), broker HiveMQ, écoute MQTT dans WildFly (`MqttListener`), détection d'anomalies. |
| **Personne 2 (Middleware & Backend)** | Persistance MongoDB via Jakarta Data, API REST (mesures, incidents, bâtiments LBS, actionneurs, Web Push), WebSocket temps réel et modèle sémantique W3C WoT. |
| **Personne 3 (Frontend & DevOps)** | PWA Client & Admin Angular 21, intégration temps réel RxJS/WebSocket, cartographie des étages (LBS SVG), Service Worker et Docker Compose. |

---

## 🛠️ 3. Stack Technologique

* **Serveur d'Applications :** WildFly 42.0.0.Final (`standalone.bat -c cot.xml`) exécuté avec Java 21 / 25
* **Base de Données :** MongoDB 7 (NoSQL Document Store)
* **Persistance & Mapping :** Jakarta Data & Eclipse JNoSQL 1.1.6
* **Messagerie IoT :** HiveMQ Community Edition (MQTT v3.1.1 / QoS 1) & Eclipse Paho
* **Temps Réel & API :** Jakarta REST, Jakarta WebSocket, Jakarta JSON-B, CDI 4
* **Standardisation :** W3C Web of Things (WoT) Thing Description v1.1
* **Sécurité & Alertes :** Web Push (VAPID RFC 8292), BouncyCastle, CORS
* **Frontend :** Angular 21, TypeScript, RxJS, Chart.js, Service Worker PWA

---

## 🚀 4. Guide de Démarrage Rapide

### Prérequis
* Docker & Docker Compose
* JDK 21 ou JDK 25 configuré (`JAVA_HOME`)
* Apache Maven 3.9+
* Node.js 20+ & npm 11+
* Python 3.10+

---

### Étape 1 : Démarrer l'Infrastructure (Docker)
À la racine du projet :
```powershell
docker compose up -d
```
> Démarre MongoDB sur le port `27017` et HiveMQ sur le port `1883`.

---

### Étape 2 : Démarrer WildFly
Dans un terminal dédié :
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot"
cd C:\wildfly-42.0.0.Final\wildfly-42.0.0.Final\bin
.\standalone.bat -c cot.xml
```
> Le serveur écoute sur le port HTTP `8081` et le port d'administration `9990`.

---

### Étape 3 : Compiler et Déployer le Backend
Dans un terminal à la racine :
```powershell
mvn clean install
mvn -pl api wildfly:deploy -Dwildfly.port=9990
```

---

### Étape 4 : Lancer l'Application Frontend (PWA)
Dans un terminal dédié :
```powershell
cd frontend/admin-pwa
npm install
npm start
```
> Ouvrir dans le navigateur : **`http://localhost:4200`**

---

### Étape 5 : Lancer la Simulation IoT
Dans un terminal dédié :
```powershell
cd iot/simulator
pip install -r requirements.txt
python simulator.py --scenario gas-leak
```

---

## 📡 5. Endpoints REST & WebSocket Principaux

Base URL : `http://localhost:8081/api`

### 📊 Mesures IoT (`/readings`)
* `GET /readings?limit=50&sensorId=gas-101` : Récupère les dernières mesures enregistrées.
* `GET /readings/{id}` : Récupère une mesure par son identifiant.
* `POST /readings` : Enregistrement manuel d'une mesure (tests).

### 🚨 Incidents (`/incidents`)
* `GET /incidents?status=OPEN` : Liste des incidents actifs triés par gravité et récence.
* `GET /incidents/{id}` : Détails d'un incident.
* `PATCH /incidents/{id}` : Prise en charge (`ACKNOWLEDGED`) ou résolution (`RESOLVED`) d'un incident. *(Déclenche un événement WebSocket en direct).*

### 🗺️ Bâtiment & LBS (`/buildings`)
* `GET /buildings` : Liste des bâtiments gérés.
* `GET /buildings/{id}/rooms` : Salles avec coordonnées vectorielles (`x, y, width, height`) et issue de secours conseillée (`exit`).
* `GET /buildings/{id}/status` : État global de sécurité (`SAFE` ou `ALERT`).
* `GET /sensors?roomId={id}` : Capteurs installés dans une salle.

### 🎮 Actionneurs (`/rooms/{roomId}/actuators`)
* `POST /rooms/{roomId}/actuators` : Commande manuelle (`{"actuator": "VENTILATION", "action": "ON"}`).

### 🔔 Notifications Web Push (`/push`)
* `GET /push/public-key` : Récupération de la clé publique VAPID.
* `POST /push/subscriptions` : Enregistrement d'un abonnement navigateur dans MongoDB.
* `DELETE /push/subscriptions?endpoint=...` : Désabonnement.

### 🌐 W3C Web of Things (`/td`)
* `GET /td` : Fiche officielle W3C Thing Description (`application/td+json`).
* `GET /td/schemas/{name}` : Accès aux schémas JSON formels (`property-schema`, `action-schema`, `event-schema`).

### ⚡ WebSocket Temps Réel
* **URL :** `ws://localhost:8081/api/ws/events`
* Diffuse en temps réel les flux :
  * Mesures : `{"kind": "reading", "data": {...}}`
  * Incidents : `{"kind": "incident", "change": "CREATED|UPDATED|RESOLVED", "data": {...}}`
  * Actionneurs : `{"kind": "actuator", "data": {...}}`

---

## 📂 6. Structure du Dépôt

```text
SmartBuildingEmergency/
├── api/                  # Module WAR : Endpoints REST, WebSocket, MqttListener, Filtres CORS
├── core/                 # Module JAR : Entités NoSQL, Repositories Jakarta Data, Services métier, Événements CDI
├── iam/                  # Module WAR : Service d'authentification et gestion des accès (Étape 3)
├── frontend/
│   ├── admin-pwa/        # Application PWA Angular (Dashboard, Incidents, Plan LBS, Alertes)
│   └── client-pwa/       # Application PWA Occupant
├── iot/
│   └── simulator/        # Simulateur de capteurs Python (Paho MQTT)
├── wot/                  # Standard W3C Web of Things (Thing Description et Schémas JSON)
│   ├── schemas/          # property-schema.json, action-schema.json, event-schema.json
│   └── smart-building.td.jsonld
├── docker-compose.yml    # Configuration des services Docker (MongoDB 7 & HiveMQ CE)
├── ATTRIBUTION.md        # Déclaration académique obligatoire de l'équipe (SUP'COM)
└── pom.xml               # POM Parent Maven
```

---

## 📜 7. Intégrité Académique
Consultez le fichier [`ATTRIBUTION.md`](./ATTRIBUTION.md) pour le détail de l'attribution des tâches, des outils d'assistance utilisés et de l'engagement sur l'honneur de l'équipe.