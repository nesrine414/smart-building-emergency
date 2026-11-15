# 🏢 Smart Building Emergency System (SBE)

<div align="center">

![Project Status](https://img.shields.io/badge/status-milestone--3--validated-brightgreen)
![Java Version](https://img.shields.io/badge/Java-21%20%7C%2025-orange)
![WildFly](https://img.shields.io/badge/WildFly-42.0.0.Final-red)
![Jakarta EE](https://img.shields.io/badge/Jakarta%20EE-10-blue)
![Ollama](https://img.shields.io/badge/Local%20AI-Ollama%20%7C%20LangChain4j-purple)
![MQTT](https://img.shields.io/badge/MQTT-HiveMQ%20%7C%20TLS-008080)
![MongoDB](https://img.shields.io/badge/Database-MongoDB%207-green)
![Node-RED](https://img.shields.io/badge/Edge-Node--RED%20Multi--Edge-8f0000)

**Système IoT distribué et intelligent de gestion des urgences en bâtiment connecté (Incendie & Fuite de Gaz) avec Passerelle Multi-Edge, Persistance NoSQL, WebSocket temps réel, Sécurité RBAC/TLS et Agent IA Local (LLMOps).**

*Module : Cloud of Things (COT) · Année Académique 2026/2027 · SUP'COM*

</div>

---

## 📋 Table des Matières

- [Vue d'ensemble](#-vue-densemble)
- [Équipe et Répartition](#-équipe-et-répartition)
- [Architecture Globale](#-architecture-globale)
- [Fonctionnalités Validées (Jalons 1, 2 & 3)](#-fonctionnalités-validées-jalons-1-2--3)
- [Stack Technologique](#-stack-technologique)
- [Structure du Dépôt](#-structure-du-dépôt)
- [Guide de Démarrage Rapide](#-guide-de-démarrage-rapide)
- [Documentation des API & WebSocket](#-documentation-des-api--websocket)
- [Sécurité & RBAC](#-sécurité--rbac)
- [Pipeline MLOps & Agent IA Local](#-pipeline-mlops--agent-ia-local)
- [Passerelle Multi-Edge (Node-RED)](#-passerelle-multi-edge-node-red)

---

## 🌟 Vue d'ensemble

Le projet **Smart Building Emergency (SBE)** est une plateforme IoT temps réel conçue pour détecter, analyser et gérer les situations d'urgence critique (incendies, fuites de gaz toxiques) au sein d'un bâtiment intelligent multi-étages.

### Points Forts du Système
- 🌐 **Architecture Multi-Edge Décentralisée :** Prétraitement local des capteurs par étage via Node-RED.
- ⚡ **Temps Réel & Réactif :** Diffusion instantanée des incidents et mesures via WebSocket (`/api/ws/events`) et bus d'événements CDI.
- 🔒 **Sécurité Industrielle Bout-en-Bout :** Chiffrement MQTTS (TLS 8883), isolation des topics HiveMQ File RBAC, et authentification JWT (HS256) avec rôles stricts (`ADMIN` vs `OCCUPANT`).
- 🤖 **Agent IA Local Autonome & LLMOps :** Intégration de LangChain4j connecté à un LLM local (Ollama `llama3.2:3b` / `qwen2.5:7b`), capable d'exécuter des outils en lecture seule pour assister les secours, avec traçabilité complète et boucle de feedback utilisateur.
- 📐 **Standard W3C WoT :** Modélisation conforme W3C Web of Things Thing Description v1.1.

---

## 👥 Équipe et Répartition

| Membre | Rôle & Périmètre d'Intervention |
| :--- | :--- |
| **Personne 1 : Inès BESBES** | **IoT, MLOps & Sécurité Broker :** Simulateur Python multi-scénarios, Pipeline MLOps d'inférence de risque incendie (`infer.py`, Random Forest monotone), Sécurité MQTT TLS/RBAC (`infra/mqtt`). |
| **Personne 2 : Nesrine HAMROUNI** | **Middleware, Stockage, IAM & Agent IA :** Backend WildFly 42 (`cot.xml`), Jakarta Data + JNoSQL 1.1.6, MongoDB 7, API REST, WebSocket temps réel, Module IAM/JWT/RBAC, Agent IA LangChain4j + métriques LLMOps, Passerelle Multi-Edge Node-RED. |
| **Personne 3 : Rihem BEN SOUISSI** | **Frontend PWA & Cartographie LBS :** Applications Web Progressives Angular (Front Office Occupant & Back Office Admin), Cartographie vectorielle SVG des étages (LBS), Notifications Web Push (VAPID). |

---

## 🏗️ Architecture Globale

```text
┌────────────────────────────────────────────────────────────────────────┐
│                   NIVEAU 1 : MULTI-EDGE GATEWAYS                       │
│                                                                        │
│   [Étage 1 : room-101/102]                 [Étage 2 : room-201]        │
│   Capteurs Gaz & Température               Capteurs Fumée & Temp       │
│               │                                       │                │
│               ▼                                       ▼                │
│   ┌───────────────────────┐               ┌───────────────────────┐    │
│   │ Node-RED Edge Étage 1 │               │ Node-RED Edge Étage 2 │    │
│   └───────────┬───────────┘               └───────────┬───────────┘    │
└───────────────┼───────────────────────────────────────┼────────────────┘
                │                                       │
                ▼ (MQTT standard / Contrat 3)           ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   NIVEAU 2 : BROKER & MLOps PIPELINE                   │
│                                                                        │
│                    ┌────────────────────────────┐                      │
│                    │  Broker HiveMQ CE          │                      │
│                    │  Port 1883 / TLS 8883      │                      │
│                    │  Extension File RBAC       │                      │
│                    └──────┬───────────────▲─────┘                      │
│                           │               │                            │
│                           │               │ Score RISK                 │
│                           ▼               │ (Contrat 6)                │
│                    ┌──────────────────────┴─────┐                      │
│                    │  Pipeline MLOps (infer.py) │                      │
│                    │  Random Forest Monotone    │                      │
│                    └────────────────────────────┘                      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ (MqttListener)
┌────────────────────────────────────────────────────────────────────────┐
│                   NIVEAU 3 : MIDDLEWARE CENTRAL (WildFly 42)           │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │ Serveur WildFly (Port 8081 / cot.xml)                          │   │
│   │                                                                │   │
│   │  ├── Jakarta REST Endpoints (/readings, /incidents, /ai...)    │   │
│   │  ├── CDI Event Bus (ReadingSaved, IncidentChanged)             │   │
│   │  ├── WebSocket Server Endpoint (/ws/events)                    │   │
│   │  ├── SecurityFilter (Contrôle d'accès JWT RBAC)                │   │
│   │  └── Agent IA Autonome (LangChain4j + 3 Tools en lecture)      │   │
│   └───────────────────────┬────────────────────────▲───────────────┘   │
└───────────────────────────┼────────────────────────┼───────────────────┘
                            │                        │
            Persistance     ▼                        ▼ Inférence locale
   ┌───────────────────────────────┐     ┌───────────────────────┐
   │ Base de Données MongoDB 7     │     │ Serveur Local Ollama  │
   │ (Jakarta Data & JNoSQL 1.1.6) │     │ (llama3.2:3b / port 11434)
   └───────────────────────────────┘     └───────────────────────┘
                            │
                            ▼ (HTTP REST & WebSocket sécurisé)
┌────────────────────────────────────────────────────────────────────────┐
│                   NIVEAU 4 : APPLICATIONS CLIENTES (PWA)               │
│                                                                        │
│   [Front Office Occupant]                 [Back Office Admin]          │
│   http://www.smartbuilding.local:4200     http://admin.smartbuilding...│
└────────────────────────────────────────────────────────────────────────┘
```

---

## ✨ Fonctionnalités Validées (Jalons 1, 2 & 3)

### Jalon 1 — Ingestion IoT & Persistance NoSQL
- [x] Configuration serveur WildFly 42 sur configuration personnalisée `cot.xml` (port `8081`).
- [x] Base NoSQL MongoDB (`smartbuilding`) interfacée avec **Jakarta Data** et **Eclipse JNoSQL 1.1.6**.
- [x] Ingestion des mesures capteurs via MQTT et API REST (`/api/readings`).
- [x] Simulateur IoT Python multi-capteurs (Gaz, Fumée, Température, Humidité, Mouvement).

### Jalon 2 — Événements, WebSocket, Actionneurs & WoT
- [x] Cycle de vie complet des incidents (`OPEN`, `ACKNOWLEDGED`, `RESOLVED`) avec sévérité dynamique (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).
- [x] Architecture événementielle découplée CDI 4 (`@Observes ReadingSaved`, `IncidentChanged`).
- [x] Diffusion temps réel bidirectionnelle via **Jakarta WebSocket** (`/api/ws/events`).
- [x] Boucle de rétroaction actionneurs (ventilation automatique / manuelle lors d'une fuite).
- [x] Cartographie LBS vectorielle des salles (`x, y, width, height, exit`).
- [x] Fiche officielle **W3C Web of Things (WoT)** Thing Description (`/api/td`).

### Jalon 3 — Sécurité Avancée, MLOps, Agent IA & Multi-Edge
- [x] **Sécurité IAM / RBAC :** Hachage des mots de passe en **PBKDF2** (100k itérations), jetons **JWT HS256**, et filtre d'autorisation strict (`401 Unauthorized`, `403 Forbidden` pour les non-admins).
- [x] **Agent IA Autonome Local :** LangChain4j connecté à **Ollama** (`llama3.2:3b`), doté de 3 outils de consultation sans hallucination (`getRecentReadings`, `getRoomEvacuationExit`, `getRiskScore`).
- [x] **Traçabilité LLMOps :** Enregistrement MongoDB de chaque inférence (latence, tokens in/out, outils appelés), endpoint de feedback utilisateur (votes 👍 / 👎) et statistiques consolidées (`/api/ai/stats`).
- [x] **Pipeline MLOps (Inès) :** Entraînement de forêt aléatoire à contrainte de monotonie (`train.py`) et calcul en direct du score `RISK` (`infer.py`).
- [x] **Sécurité Broker MQTT (Inès) :** Extension File RBAC avec comptes étanches (`sbe-backend`, `sbe-device`, `sbe-ml`) et certificats X.509 TLS 8883.
- [x] **Passerelle Multi-Edge (Node-RED) :** Flux visuel modélisant les passerelles locales d'étages (Étage 1 & Étage 2).

---

## 🛠️ Stack Technologique

| Domaine | Technologies Utilisées |
| :--- | :--- |
| **Serveur d'Applications** | WildFly 42.0.0.Final (`standalone.bat -c cot.xml`), Java 21 / 25 |
| **Standards Java** | Jakarta EE 10 (REST 3.1, CDI 4.0, WebSocket 2.1, Data 1.0, JSON-B 3.0) |
| **Persistance NoSQL** | Eclipse JNoSQL 1.1.6, MongoDB 7.0 (Docker) |
| **Broker IoT** | HiveMQ Community Edition (MQTT v3.1.1 & v5.0, TLS 8883, File RBAC 4.6.19) |
| **Edge Computing** | Node-RED 4.x (Docker), Paho MQTT |
| **Intelligence Artificielle** | LangChain4j 0.35.0, Ollama local (`llama3.2:3b`, `qwen2.5:7b`) |
| **Machine Learning / MLOps**| Python 3.11+, Scikit-Learn (Random Forest monotone), Pandas, Joblib |
| **Sécurité & IAM** | PBKDF2 (100 000 itérations), JJWT / Nimbus-JOSE (HS256), X.509 Certificates |
| **Frontend** | Angular 21, TypeScript, RxJS, PWA Service Worker, Web Push (VAPID) |

---

## 📂 Structure du Dépôt

```text
SmartBuildingEmergency/
├── Middelware/                  # Code source Java Backend
│   ├── core/                    # Module JAR : Entités JNoSQL, Repositories, Services métier
│   ├── api/                     # Module WAR : Endpoints REST, WebSocket, MqttListener, LangChain4j Agent
│   └── iam/                     # Module WAR : Service d'authentification JWT & PBKDF2
├── nodered/                     # Passerelle Multi-Edge Node-RED
│   ├── flows.json               # Flux Multi-Edge (Étage 1 & Étage 2)
│   ├── settings.js              # Configuration d'exécution
│   └── package.json             # Métadonnées Node-RED
├── iot/
│   ├── simulator/               # Simulateur de capteurs Python multi-scénarios
│   ├── mlops/                   # Pipeline ML (train.py, infer.py, modèles et métriques)
│   └── llm/                     # Diagnostic du serveur local Ollama (check_ollama.py)
├── infra/
│   └── mqtt/                    # Sécurité HiveMQ (File RBAC, génération de certificats TLS)
├── frontend/
│   ├── client-pwa/              # PWA Angular Occupant (Évacuation & Consultation)
│   └── admin-pwa/               # PWA Angular Administrateur (Gestion des incidents & Supervision)
├── wot/                         # Schémas et fiches W3C Web of Things
├── docker-compose.yml           # Services Docker (MongoDB 7, HiveMQ CE, Node-RED)
├── docker-compose.secure.yml    # Configuration TLS & RBAC pour HiveMQ
└── pom.xml                      # POM Parent Maven multi-modules
```

---

## 🚀 Guide de Démarrage Rapide

### 1. Démarrer l'infrastructure Docker
```powershell
docker compose up -d
```
> Lance MongoDB (`27017`), HiveMQ (`1883`) et la passerelle Node-RED (`1880`).

### 2. Démarrer Ollama (Modèle Local)
```powershell
ollama run llama3.2:3b
```
> Le serveur d'IA répond sur `http://localhost:11434`.

### 3. Démarrer le Serveur WildFly
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot"
cd C:\wildfly-42.0.0.Final\wildfly-42.0.0.Final\bin
.\standalone.bat -c cot.xml
```
> WildFly écoute sur le port HTTP `8081` et le port d'administration `9990`.

### 4. Compiler et Déployer le Backend
```powershell
mvn clean install
mvn -pl Middelware/api wildfly:deploy -Dwildfly.port=9990
```

### 5. Lancer l'inférence MLOps (Score de Risque)
```powershell
cd iot/mlops
python infer.py
```

### 6. Ouvrir la Passerelle Multi-Edge Node-RED
Rendez-vous sur votre navigateur à l'adresse : **`http://localhost:1880`**  
Vous observez les flux préconfigurés pour l'Étage 1 et l'Étage 2 connectés en direct à HiveMQ !

---
---

## 🌐 Configuration Réseau : DNS Local & Roadmap Production

Le système est conçu avec une architecture multi-domaines étanche séparant l'API, le module IAM, l'application Occupant et l'administration.

### 1. Configuration en Environnement Local (Développement & Démo)
En environnement de développement, nous utilisons l'extension réservée aux réseaux privés **`.local`** (conforme à la norme **RFC 6762**). 

Pour permettre la résolution locale, ajoutez les lignes suivantes dans votre fichier d'hôtes système :
* **Windows :** `C:\Windows\System32\drivers\etc\hosts` (en mode Administrateur)
* **Linux / macOS :** `/etc/hosts`

```text
# Smart Building Emergency - Résolution locale
127.0.0.1   api.smartbuilding.local
127.0.0.1   iam.smartbuilding.local
127.0.0.1   www.smartbuilding.local
127.0.0.1   admin.smartbuilding.local
```

### 2. Stratégie de Migration vers la Production (`.me`)
Pour le passage en production cloud (AWS EC2 / VPS), l'architecture est prête pour une transition transparente vers un domaine de premier niveau public (ex: **`smartbuilding.me`**) :

```text
    Environnement Local                   Environnement Production
┌───────────────────────────┐         ┌──────────────────────────────┐
│ api.smartbuilding.local   │   ──►   │ api.smartbuilding.me (HTTPS) │
│ iam.smartbuilding.local   │   ──►   │ iam.smartbuilding.me (HTTPS) │
│ www.smartbuilding.local   │   ──►   │ www.smartbuilding.me (PWA)   │
│ admin.smartbuilding.local │   ──►   │ admin.smartbuilding.me (PWA) │
└───────────────────────────┘         └──────────────────────────────┘
    (Fichier hosts / 127.0.0.1)           (DNS Publics & Reverse Proxy Nginx)
```

**Étapes de déploiement en production :**
1. **Enregistrements DNS (A Records) :** Redirection de chaque sous-domaine (`api`, `iam`, `www`, `admin`) vers l'adresse IP fixe du serveur Cloud.
2. **Terminaison SSL (TLS 1.3) :** Déploiement d'un reverse proxy **Nginx** avec certificats **Let's Encrypt (Certbot)** garantissant un score SSL A+.
3. **Mise à jour des règles CORS :** Bascule des origines autorisées dans `CorsFilter.java` vers les URLs HTTPS publiques.
## 📡 Documentation des API & WebSocket

Base URL : `http://api.smartbuilding.local:8081/api`

### 🔐 Authentification & IAM (`/iam/api/auth`)
| Méthode | Endpoint | Rôle Requis | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/iam/api/auth/login` | Public | Authentifie un utilisateur et retourne un jeton JWT valide 8h. |

### 📊 Télémétrie des Capteurs (`/api/readings`)
| Méthode | Endpoint | Rôle Requis | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/readings?limit=50` | Authentifié | Historique des mesures triées par date décroissante. |
| `POST` | `/api/readings` | Authentifié | Injection manuelle d'une mesure. |

### 🚨 Gestion des Incidents (`/api/incidents`)
| Méthode | Endpoint | Rôle Requis | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/incidents?status=OPEN` | Authentifié | Liste des incidents actifs triés par sévérité. |
| `GET` | `/api/incidents/{id}` | Authentifié | Détail d'un incident spécifique. |
| `PATCH` | `/api/incidents/{id}` | **ADMIN** | Résolution ou prise en charge (`403 Forbidden` pour Occupant). |

### 🤖 Agent IA & LLMOps (`/api/ai`)
| Méthode | Endpoint | Rôle Requis | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/ai/explain-incident` | Authentifié | Déclenche l'Agent IA pour analyser un incident avec ses outils. |
| `POST` | `/api/ai/ask` | Authentifié | Pose une question ouverte sur la sécurité du bâtiment. |
| `POST` | `/api/ai/feedback` | Authentifié | Soumet un vote utilisateur (UP 👍 / DOWN 👎). |
| `GET` | `/api/ai/stats` | **ADMIN** | Statistiques LLMOps (nombre de requêtes, latence moyenne, taux d'erreurs). |

### ⚡ WebSocket Temps Réel
* **URL :** `ws://api.smartbuilding.local:8081/api/ws/events?token=<VOTRE_JWT>`
* **Format des événements diffusés :**
  ```json
  {
    "kind": "incident",
    "change": "CREATED",
    "data": {
      "id": "inc-01",
      "type": "GAS_LEAK",
      "severity": "HIGH",
      "roomId": "room-101",
      "status": "OPEN"
    }
  }
  ```

---

## 🔒 Sécurité & RBAC

1. **Isolation des Rôles :**
   - **`ADMIN`** (`admin / admin123`) : Accès total, acquittement/résolution des incidents, déclenchement des actionneurs, accès aux statistiques LLMOps.
   - **`OCCUPANT`** (`occupant / occupant123`) : Consultation des alertes, questionnement de l'agent IA, soumission de feedbacks.
2. **Protection stricte CORS :** Seules les origines déclarées (`http://www.smartbuilding.local:4200` et `http://admin.smartbuilding.local:4201`) sont autorisées.
3. **Sécurité Réseau MQTT :** Chiffrement TLS (port 8883) et droits par topics cloisonnés (`sbe-device`, `sbe-ml`, `sbe-backend`).

---

## 🧠 Pipeline MLOps & Agent IA Local

### 1. Le Modèle ML de Risque (`RandomForestClassifier`)
- Entraîné sur le dataset public *Smoke Detection IoT* (62 000+ points).
- **Contrainte de monotonie (`monotonic_cst`) :** Garantit mathématiquement que plus la chaleur ou les gaz montent, plus le risque calculé augmente (aucun paradoxe thermique).
- **Performance :** Précision de 87.8%, Rappel de 85.6%, et **AUC de 0.906**.

### 2. Les Outils de l'Agent IA (LangChain4j)
L'agent d'urgence interroge 3 outils métier sans inventer de données :
1. `getRecentReadings(roomId)` : Retourne la dernière mesure de chaque capteur de la salle.
2. `getRoomEvacuationExit(roomId)` : Identifie l'issue de secours optimale selon les plans vectoriels LBS.
3. `getRiskScore(roomId)` : Récupère le score calculé en direct par le modèle Machine Learning.

---

## 🌐 Passerelle Multi-Edge (Node-RED)

Pour éviter qu'un incident réseau n'aveugle le système central, Node-RED modélise l'architecture distribuée :
- **Edge Passerelle Étage 1 :** Réception et lissage local des capteurs des salles 101 et 102.
- **Edge Passerelle Étage 2 :** Prétraitement et surveillance locale des capteurs de la salle 201.
- **Publication Standardisée :** Envoi vers HiveMQ au format convenu du Contrat 3.

---

<div align="center">

**Smart Building Emergency System** · Développé avec passion pour le module Cloud of Things (COT) · SUP'COM 2026/2027

</div>