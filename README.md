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
- [Sécurité du Broker MQTT & PKI Locale](#-sécurité-du-broker-mqtt--pki-locale)
- [Pipeline MLOps & Agent IA Local](#-pipeline-mlops--agent-ia-local)
- [Passerelle Multi-Edge (Node-RED)](#-passerelle-multi-edge-node-red)

---

## 🌟 Vue d'ensemble

Le projet **Smart Building Emergency (SBE)** est une plateforme IoT temps réel conçue pour détecter, analyser et gérer les situations d'urgence critique (incendies, fuites de gaz toxiques) au sein d'un bâtiment intelligent multi-étages.

### Points Forts du Système
- 🌐 **Architecture Multi-Edge Décentralisée :** Prétraitement local des capteurs par étage via Node-RED.
- ⚡ **Temps Réel & Réactif :** Diffusion instantanée des incidents et mesures via WebSocket (`/api/ws/events`) et bus d'événements CDI.
- 🔒 **Sécurité Industrielle Bout-en-Bout :** Chiffrement MQTTS (TLS 8883) validé par une autorité de certification (CA) locale, isolation des topics HiveMQ File RBAC, et authentification JWT (HS256) avec rôles stricts (`ADMIN` vs `OCCUPANT`).
- 🤖 **Agent IA Local Autonome & LLMOps :** Intégration de LangChain4j connecté à un LLM local (Ollama `llama3.2:3b` / `qwen2.5:7b`), capable d'exécuter des outils en lecture seule pour assister les secours, avec traçabilité complète et boucle de feedback utilisateur.
- 📐 **Standard W3C WoT :** Modélisation conforme W3C Web of Things Thing Description v1.1.

---

## 👥 Équipe et Répartition

| Membre | Rôle & Périmètre d'Intervention |
| :--- | :--- |
| **Personne 1 : Inès BESBES** | **IoT, MLOps & Sécurité du Broker :** Simulateur Python multi-scénarios avec actionneurs simulés (option `--tls`), pipeline MLOps d'inférence du risque incendie (`train.py`, `infer.py`, Random Forest monotone), vérification du serveur LLM local (`check_ollama.py`), sécurité MQTT (File RBAC, **autorité de certification locale, certificats X.509 et TLS 8883**, contrôle automatique et audit TLS) dans `infra/mqtt`. |
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
│                    │  Certificat signé par la   │                      │
│                    │  CA locale du projet       │                      │
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
- [x] Boucle de rétroaction actionneurs (ventilation automatique / manuelle lors d'une fuite) : le simulateur joue le rôle des appareils, reçoit les commandes sur `.../cmd` et confirme leur état sur `.../actuator`.
- [x] Cartographie LBS vectorielle des salles (`x, y, width, height, exit`).
- [x] Fiche officielle **W3C Web of Things (WoT)** Thing Description (`/api/td`).

### Jalon 3 — Sécurité Avancée, MLOps, Agent IA & Multi-Edge
- [x] **Sécurité IAM / RBAC :** Hachage des mots de passe en **PBKDF2** (100k itérations), jetons **JWT HS256**, et filtre d'autorisation strict (`401 Unauthorized`, `403 Forbidden` pour les non-admins).
- [x] **Agent IA Autonome Local :** LangChain4j connecté à **Ollama** (`llama3.2:3b`), doté de 3 outils de consultation sans hallucination (`getRecentReadings`, `getRoomEvacuationExit`, `getRiskScore`).
- [x] **Traçabilité LLMOps :** Enregistrement MongoDB de chaque inférence (latence, tokens in/out, outils appelés), endpoint de feedback utilisateur (votes 👍 / 👎) et statistiques consolidées (`/api/ai/stats`).
- [x] **Pipeline MLOps :** Entraînement de forêt aléatoire à contrainte de monotonie (`train.py`) et calcul en direct du score `RISK` (`infer.py`), avec versionnement du modèle et métriques (`metrics-v1.json`).
- [x] **Sécurité Broker MQTT :** Extension File RBAC avec comptes étanches (`sbe-backend`, `sbe-device`, `sbe-ml`), **autorité de certification locale (CA)**, certificat X.509 du broker, TLS 1.2/1.3 sur le port 8883, truststore Java pour le backend, rejet des clients anonymes et des messages `retained`.
- [x] **Validation TLS :** Chaîne de certification vérifiée avec la CA du projet (`openssl s_client`, `Verification: OK`) et audit de la configuration TLS avec `sslyze` (voir [Sécurité du Broker MQTT & PKI Locale](#-sécurité-du-broker-mqtt--pki-locale)).
- [x] **Vérification du LLM local :** `check_ollama.py` contrôle la capacité `tools`, un appel d'outil réel (`tool_calls`), la latence et le délai d'attente conseillé.
- [x] **Passerelle Multi-Edge (Node-RED) :** Flux visuel modélisant les passerelles locales d'étages (Étage 1 & Étage 2).

---

## 🛠️ Stack Technologique

| Domaine | Technologies Utilisées |
| :--- | :--- |
| **Serveur d'Applications** | WildFly 42.0.0.Final (`standalone.bat -c cot.xml`), Java 21 / 25 |
| **Standards Java** | Jakarta EE 10 (REST 3.1, CDI 4.0, WebSocket 2.1, Data 1.0, JSON-B 3.0) |
| **Persistance NoSQL** | Eclipse JNoSQL 1.1.6, MongoDB 7.0 (Docker) |
| **Broker IoT** | HiveMQ Community Edition (MQTT v3.1.1 & v5.0, TLS 8883, File RBAC 4.6.19) |
| **PKI & Chiffrement** | CA locale (RSA 3072), certificat serveur X.509 (RSA 2048), `keytool`, TLS 1.2 / 1.3 (AES-GCM, échange X25519 / ECDHE), `openssl`, `sslyze` |
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
│   ├── simulator/               # Simulateur de capteurs Python multi-scénarios (option --tls)
│   ├── mlops/                   # Pipeline ML (features.py, train.py, infer.py, modèles et métriques)
│   └── llm/                     # Diagnostic du serveur local Ollama (check_ollama.py)
├── infra/
│   └── mqtt/                    # Sécurité du broker HiveMQ
│       ├── hivemq/              #   config.xml (1883 + 8883) et config-tls-only.xml (8883 seul)
│       ├── rbac/                #   Réglages de l'extension File RBAC (credentials.xml généré, hors Git)
│       ├── certs/               #   CA locale, certificat du broker, keystore et truststore (généré, hors Git)
│       ├── gen_credentials.py   #   Crée le .env puis les comptes et droits RBAC
│       ├── install-rbac.ps1     #   Télécharge l'extension File RBAC et vérifie son SHA-256
│       ├── gen-certs.ps1        #   Génère la CA locale et le certificat du broker (keytool)
│       ├── backend-env.ps1      #   Variables MQTT de WildFly (compte, truststore)
│       └── check_mqtt_security.py #  Contrôle automatique de la sécurité du broker
├── frontend/
│   ├── client-pwa/              # PWA Angular Occupant (Évacuation & Consultation)
│   └── admin-pwa/               # PWA Angular Administrateur (Gestion des incidents & Supervision)
├── wot/                         # Schémas et fiches W3C Web of Things
├── docker-compose.yml           # Services Docker (MongoDB 7, HiveMQ CE, Node-RED) : broker ouvert
├── docker-compose.secure.yml    # Surcouche sécurisée pour HiveMQ (TLS 8883, File RBAC, certificats)
├── .env.example                 # Liste des secrets attendus, sans valeurs (le vrai .env n'est jamais sur Git)
└── pom.xml                      # POM Parent Maven multi-modules
```

---

## 🚀 Guide de Démarrage Rapide

### 1. Démarrer l'infrastructure Docker
```powershell
docker compose up -d
```
> Lance MongoDB (`27017`), HiveMQ (`1883`) et la passerelle Node-RED (`1880`). Cette commande seule démarre le broker **ouvert** (sans mot de passe). Pour la démo avec le broker sécurisé (TLS + RBAC), suivre [la mise en place du broker sécurisé](#2-mise-en-place-pas-à-pas-windows-powershell).

### 2. Démarrer Ollama (Modèle Local)
```powershell
ollama run llama3.2:3b
```
> Le serveur d'IA répond sur `http://localhost:11434`. Pour vérifier la capacité d'appel d'outils et la latence : `python iot\llm\check_ollama.py`.

### 3. Démarrer le Serveur WildFly
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot"
cd C:\wildfly-42.0.0.Final\wildfly-42.0.0.Final\bin
.\standalone.bat -c cot.xml
```
> WildFly écoute sur le port HTTP `8081` et le port d'administration `9990`.
>
> **Avec le broker sécurisé :** dans la même console, avant de lancer `standalone.bat`, depuis la racine du dépôt : `. .\infra\mqtt\backend-env.ps1` (le point au début est obligatoire). Ce script charge le compte `sbe-backend`, son mot de passe et le truststore de la CA du projet.

### 4. Compiler et Déployer le Backend
```powershell
mvn clean install
mvn -pl Middelware/api wildfly:deploy -Dwildfly.port=9990
```

### 5. Lancer l'inférence MLOps (Score de Risque)
```powershell
cd iot/mlops
python infer.py          # broker ouvert
python infer.py --tls    # broker sécurisé (compte sbe-ml)
```

### 6. Ouvrir la Passerelle Multi-Edge Node-RED
Rendez-vous sur votre navigateur à l'adresse : **`http://localhost:1880`**  
Vous observez les flux préconfigurés pour l'Étage 1 et l'Étage 2 connectés en direct à HiveMQ !

### 7. Simuler un incident
```powershell
python iot\simulator\simulator.py --scenario gas-leak          # broker ouvert
python iot\simulator\simulator.py --tls --scenario gas-leak    # broker sécurisé (compte sbe-device)
python iot\simulator\simulator.py --tls --scenario fire --rooms 201,101,102
```
> Scénarios : `normal`, `gas-leak` (fuite de gaz en salle 101, la ventilation fait redescendre le gaz) et `fire` (fumée et température qui montent). Le simulateur affiche les commandes d'actionneurs reçues du backend (`>>> Salle 101 : ventilation ON`).

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
127.0.0.1   mqtt.smartbuilding.local
```
> La dernière ligne (`mqtt.smartbuilding.local`) est facultative : le certificat du broker est valable pour ce nom, ce qui permet de tester la vérification du nom d'hôte TLS sans passer par `localhost`.

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
4. **Broker MQTT :** en production, remplacer la CA locale par un certificat émis par une autorité publique (ou conserver la CA privée en la distribuant aux appareils), et fermer définitivement le port 1883.

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

> Les mesures de type `RISK` (`sensorId` `ml-101`, unité `score`, valeur entre 0 et 1) publiées par `infer.py` apparaissent aussi dans cet historique. Ce n'est pas une alerte : les incidents restent créés par les règles de détection.

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
3. **Sécurité Réseau MQTT :** Chiffrement TLS (port 8883) avec un certificat signé par une CA locale, et droits par topics cloisonnés (`sbe-device`, `sbe-ml`, `sbe-backend`). Détails dans la section suivante.

---

## 🔏 Sécurité du Broker MQTT & PKI Locale

> Partie (`infra/mqtt`). HiveMQ Community Edition n'a ni mot de passe ni contrôle d'accès intégré : son image Docker accepte tous les clients grâce à une extension « allow-all ». Nous la remplaçons par l'extension officielle **File RBAC** (comptes, mots de passe hachés, droits par topic) et nous ajoutons un port **TLS 8883** dont le certificat est signé par une **autorité de certification (CA) propre au projet**.

### 1. Comptes et droits par topic

| Compte | Utilisé par | Peut publier | Peut s'abonner |
| :--- | :--- | :--- | :--- |
| `sbe-backend` | WildFly (`MqttListener`) | `smartbuilding/+/+/cmd` | `smartbuilding/#` |
| `sbe-device` | Simulateur, ESP32 | Mesures (`GAS`, `SMOKE`, `TEMPERATURE`, `HUMIDITY`, `MOTION`) et `.../actuator` | `smartbuilding/+/+/cmd` |
| `sbe-ml` | `iot/mlops/infer.py` | `smartbuilding/+/+/RISK` | Les 5 types de mesures |

- Aucun message `retained` n'est accepté : un appareil ne peut pas laisser une fausse valeur sur le broker.
- Un client qui publie hors de ses droits reçoit un refus puis est déconnecté par HiveMQ.
- Les mots de passe sont générés aléatoirement (24 caractères) dans un fichier `.env` jamais versionné, puis hachés dans `credentials.xml`.
- En cas de panne de configuration (keystore illisible, `credentials.xml` absent), le broker refuse les clients ou ne démarre pas : **il ne repasse jamais en mode ouvert**.

### 2. Infrastructure à clés publiques (PKI) locale

Pour que chaque client puisse **vérifier qu'il parle bien au vrai broker** (et pas à un imposteur), le projet crée sa propre autorité de certification avec `infra/mqtt/gen-certs.ps1` (outil `keytool` du JDK, aucun logiciel supplémentaire).

```text
┌──────────────────────────────────────────┐
│  CA locale : « Smart Building Emergency  │
│  Local CA »  (auto-signée, RSA 3072)     │
│  Valide 10 ans · pathlen:0               │
│  Usage : keyCertSign, cRLSign            │
└───────────────────┬──────────────────────┘
                    │ signe
                    ▼
┌──────────────────────────────────────────┐
│  Certificat du broker HiveMQ             │
│  CN=localhost, O=SupCom, C=TN            │
│  RSA 2048 · valide 825 jours             │
│  Usage : digitalSignature, keyEncipherment│
│  EKU : serverAuth                        │
│  SAN : localhost, hivemq,                │
│        mqtt.smartbuilding.local,         │
│        127.0.0.1 (+ IP du PC si définie) │
└──────────────────────────────────────────┘
```

| Paramètre | CA locale | Certificat du broker |
| :--- | :--- | :--- |
| Algorithme et taille de clé | RSA 3072 bits | RSA 2048 bits |
| Validité | 3650 jours (10 ans) | 825 jours |
| Extensions | `basicConstraints: CA:true, pathlen:0` ; `keyUsage: keyCertSign, cRLSign` | `keyUsage: digitalSignature, keyEncipherment` ; `extendedKeyUsage: serverAuth` ; `subjectAltName` |
| Signature | Auto-signée | Signée par la CA (SHA-384 avec RSA) |

> `pathlen:0` empêche la CA de délivrer des sous-autorités : elle ne peut signer que des certificats finaux. Le certificat du broker est valable pour `localhost`, pour le nom du conteneur Docker (`hivemq`) et pour `mqtt.smartbuilding.local`. Un client qui se connecte avec un autre nom (par exemple `127.0.0.2` ou le nom du PC) est refusé : il faut alors ajouter l'IP dans `MQTT_BROKER_LAN_IP` (`.env`) puis régénérer les certificats.

**Fichiers produits dans `infra/mqtt/certs/` (jamais versionnés sur Git) :**

| Fichier | Rôle | Qui l'utilise |
| :--- | :--- | :--- |
| `broker.jks` | Keystore Java : clé privée et certificat du broker | HiveMQ (monté dans le conteneur) |
| `ca.pem` | Certificat public de la CA | Simulateur et `infer.py` (Python), ESP32 (`WiFiClientSecure::setCACert`), MQTT Explorer |
| `truststore.jks` | Truststore Java contenant la CA | WildFly (`MQTT_TRUSTSTORE`) |

Seuls `ca.pem` et `truststore.jks` sont distribués aux clients : ce sont des certificats **publics**. Les clés privées (CA et broker) ne quittent jamais le PC qui héberge le broker.

### 3. Mise en place pas à pas (Windows, PowerShell)

Prérequis : Docker Desktop, un JDK 17+ (pour `keytool`), Python avec `paho-mqtt` (`pip install -r iot/simulator/requirements.txt`). Toutes les commandes se lancent **depuis la racine du dépôt**.

```powershell
# 1. Secrets de l'équipe : crée .env et remplit les mots de passe (aléatoires)
python infra\mqtt\gen_credentials.py --init-env
#    -> partager ce .env avec l'équipe par un canal privé (jamais Git)

# 2. Extension File RBAC (téléchargement + vérification de l'empreinte SHA-256)
powershell -ExecutionPolicy Bypass -File infra\mqtt\install-rbac.ps1

# 3. CA locale et certificat du broker
powershell -ExecutionPolicy Bypass -File infra\mqtt\gen-certs.ps1

# 4. Comptes et droits (à relancer après chaque changement de mot de passe dans .env)
python infra\mqtt\gen_credentials.py

# 5. Broker sécurisé
docker compose -f docker-compose.yml -f docker-compose.secure.yml up -d --force-recreate hivemq
docker logs smart-building-hivemq

# 6. Contrôle automatique de la sécurité du broker
python infra\mqtt\check_mqtt_security.py
```

Les logs de HiveMQ doivent contenir : `Disabling allow all extension`, `Extension "HiveMQ File Role Based Access Control Extension" version 4.6.19 started successfully`, `Started TCP Listener on address 0.0.0.0 and on port 1883` et `Started TCP Listener with TLS on address 0.0.0.0 and on port 8883`.

**Brancher les clients :**

| Client | Commande | Compte |
| :--- | :--- | :--- |
| Backend (WildFly) | `. .\infra\mqtt\backend-env.ps1` puis démarrage de WildFly dans la même console | `sbe-backend` |
| Simulateur | `python iot\simulator\simulator.py --tls --scenario gas-leak` | `sbe-device` |
| Score ML | `cd iot\mlops ; python infer.py --tls` | `sbe-ml` |

Logs attendus côté backend : `Connecte au broker MQTT ssl://localhost:8883` puis `Abonne au topic MQTT smartbuilding/#`. Un client qui se présente sans mot de passe ou avec un mauvais mot de passe est refusé (`Bad user name or password`).

### 4. Validation de la chaîne de certification

**Test avec la CA du projet** (c'est le bon test pour une CA privée) :

```powershell
openssl s_client -connect localhost:8883 -servername mqtt.smartbuilding.local `
  -CAfile infra\mqtt\certs\ca.pem -verify_hostname mqtt.smartbuilding.local -brief
```

Résultat obtenu :

```text
CONNECTION ESTABLISHED
Protocol version: TLSv1.3
Ciphersuite: TLS_AES_256_GCM_SHA384
Peer certificate: C=TN, O=SupCom, CN=localhost
Verification: OK
Verified peername: mqtt.smartbuilding.local
Peer Temp Key: X25519, 253 bits
```

✅ Le certificat du broker est bien signé par la CA du projet, et le nom `mqtt.smartbuilding.local` est accepté par le certificat.

### 5. Audit de la configuration TLS (`sslyze`)

Un scan `sslyze mqtt.smartbuilding.local:8883` a été réalisé sur le broker.

| Contrôle | Résultat |
| :--- | :--- |
| SSL 2.0, SSL 3.0, TLS 1.0, TLS 1.1 | ✅ Rejetés (toutes les suites refusées) |
| TLS 1.2 | ✅ 2 suites acceptées : `ECDHE-RSA-AES256-GCM-SHA384`, `ECDHE-RSA-AES128-GCM-SHA256` |
| TLS 1.3 | ✅ 2 suites acceptées : `TLS_AES_256_GCM_SHA384`, `TLS_AES_128_GCM_SHA256` |
| Confidentialité persistante (Forward Secrecy) | ✅ Supportée |
| RC4 | ✅ Non supporté |
| Compression TLS | ✅ Désactivée |
| Heartbleed, injection CCS OpenSSL, ROBOT | ✅ Non vulnérable |
| Renégociation sécurisée / DoS client | ✅ Sécurisée, non vulnérable |
| Protection contre le déclassement (`TLS_FALLBACK_SCSV`) | ✅ Supportée |
| Extended Master Secret | ✅ Supporté |
| Certificat : clé, noms | ✅ RSA 2048, SAN = `localhost`, `hivemq`, `mqtt.smartbuilding.local`, `127.0.0.1` |

**Écarts constatés et justification :** `sslyze` compare aussi le broker à la configuration « intermediate » de Mozilla, conçue pour des **sites web publics**. Voici les écarts relevés et notre décision.

| Écart signalé | Explication | Décision |
| :--- | :--- | :--- |
| Certificat non reconnu par les magasins Android, Apple, Java, Mozilla et Windows | La CA est **privée par conception** : aucun magasin public ne la connaît. Les clients du projet reçoivent la CA (`ca.pem`, `truststore.jks`) et vérifient eux-mêmes la chaîne, ce que confirme `Verification: OK` ci-dessus. | Accepté (comportement attendu) |
| Durée de vie de 825 jours (recommandation : moins de 366) | Recommandation pensée pour les certificats publics. Le certificat expire le 17/02/2029 et se régénère avec `gen-certs.ps1`. | Accepté pour le prototype ; à ramener à 365 jours en production |
| Signature `sha384WithRSA` (la liste Mozilla cite SHA-256) | La signature SHA-384 est solide. Elle vient de la taille de la clé de la CA (3072 bits). | Accepté (écart cosmétique) |
| Courbes `secp521r1` et `X448` acceptées | Courbes robustes. Les restreindre demande de configurer la JVM du conteneur HiveMQ pour un gain minime. | Accepté, à documenter |

### 6. Fermer le port 1883 (fin de la transition)

Pendant la transition, le port 1883 (sans chiffrement) reste ouvert mais **exige déjà un identifiant et un mot de passe**. Quand le backend, le simulateur et `infer.py` passent tous par le 8883 :

```powershell
# dans .env : HIVEMQ_CONFIG=config-tls-only.xml
docker compose -f docker-compose.yml -f docker-compose.secure.yml up -d --force-recreate hivemq
python infra\mqtt\check_mqtt_security.py      # attendu : « port 1883 fermé : seul le TLS est accepté »
```

### 7. Renouvellement et dépannage

- **Régénérer les certificats :** `infra\mqtt\gen-certs.ps1 -Force`, puis recréer le conteneur HiveMQ, relancer WildFly et redistribuer `ca.pem` / `truststore.jks` si la CA a changé.
- **Changer un mot de passe :** modifier le `.env`, relancer `python infra\mqtt\gen_credentials.py` (HiveMQ relit le fichier en 60 secondes, ou recréer le conteneur).

| Symptôme | Cause probable | Solution |
| :--- | :--- | :--- |
| `Bad user name or password` / `Not authorized` | Client sans identifiant, ou `.env` différent de celui qui a généré `credentials.xml` | Même `.env` pour toute l'équipe ; relancer `gen_credentials.py` |
| `PKIX path building failed` (Java) | WildFly lancé sans le truststore de la CA | Démarrer WildFly depuis une console où `backend-env.ps1` a été exécuté (avec le point devant) |
| `No subject alternative names matching ...` | Le client utilise un nom absent du certificat | Utiliser `localhost`, ou ajouter l'IP dans `MQTT_BROKER_LAN_IP` et régénérer les certificats |
| `required variable MQTT_KEYSTORE_PASSWORD is missing a value` (docker compose) | `.env` absent ou incomplet | `python infra\mqtt\gen_credentials.py --init-env` |
| HiveMQ redémarre en boucle (`Not able to open or read KeyStore`) | Certificats absents, ou mot de passe du keystore modifié après `gen-certs` | `gen-certs.ps1 -Force`, puis recréer le conteneur |

### 8. Étape suivante : ESP32

Tout est prêt côté broker pour les capteurs physiques : compte `sbe-device`, port 8883 et certificat de la CA (`certs/ca.pem`) à intégrer dans le programme de l'ESP32 (`WiFiClientSecure::setCACert`). L'ESP32 se connecte par l'adresse IP du PC sur le Wi-Fi : cette IP doit figurer dans `MQTT_BROKER_LAN_IP` (`.env`) avant de générer les certificats, pour apparaître dans le certificat du broker.

---

## 🧠 Pipeline MLOps & Agent IA Local

### 1. Le Modèle ML de Risque (`RandomForestClassifier`)
- Entraîné sur le dataset public *Smoke Detection IoT* (62 000+ points).
- **Contrainte de monotonie (`monotonic_cst`) :** Garantit mathématiquement que plus la chaleur ou les gaz montent, plus le risque calculé augmente (aucun paradoxe thermique).
- **Performance :** Précision de 87.8%, Rappel de 85.6%, et **AUC de 0.906**.
- **Versionnement et métriques :** le modèle et ses scores sont enregistrés par version (`iot/mlops/models/metrics-v1.json` : précision, rappel, F1, AUC, matrice de confusion).
- **Inférence en direct :** `infer.py` lit les mesures des salles sur MQTT et publie un score entre 0 et 1 sur `smartbuilding/{bâtiment}/{salle}/RISK` (compte `sbe-ml`, environ toutes les 5 secondes par salle). Pendant `--scenario fire`, le score de la salle concernée monte (environ 0,19 au départ à 0,72 au pic).

### 2. Les Outils de l'Agent IA (LangChain4j)
L'agent d'urgence interroge 3 outils métier sans inventer de données :
1. `getRecentReadings(roomId)` : Retourne la dernière mesure de chaque capteur de la salle.
2. `getRoomEvacuationExit(roomId)` : Identifie l'issue de secours optimale selon les plans vectoriels LBS.
3. `getRiskScore(roomId)` : Récupère le score calculé en direct par le modèle Machine Learning.

### 3. Vérification du serveur LLM local
`python iot\llm\check_ollama.py` teste le serveur Ollama avant la démo : le modèle déclare-t-il la capacité `tools`, renvoie-t-il un vrai appel d'outil (`tool_calls`), quelle est la latence, et quel délai d'attente le backend doit-il prévoir. Le rapport JSON produit sert à configurer l'agent. Modèle conseillé : `qwen2.5:7b`, ou `llama3.2:3b` avec 8 Go de RAM.

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
