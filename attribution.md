# 📜 Déclaration d'Attribution et d'Intégrité Académique
### Module : Cloud of Things (COT) · Année 2026/2027 · SUP'COM

Ce document est un **livrable obligatoire** devant être complété et mis à jour à chaque sprint par l'équipe. Il vise à cartographier de manière transparente l'origine du code, des architectures et l'usage des outils d'assistance (IA, bibliothèques tiers, codes d'archive).

---

## 👥 1. Identification de l'Équipe
*   **Projet (Nom) :** Smart Building Emergency
*   **Équipe (Numéro) :** 7
*   **Membres :**
    *   Membre 1 : Inès BESBES (Sécurité Broker MQTT TLS/RBAC, Pipeline MLOps & Inférence de Risque, Infrastructure Cloud)
    *   Membre 2 : Nesrine HAMROUNI (Agent IA Local LangChain4j/Ollama, LLMOps, Sécurité IAM/OAuth2 PKCE/OIDC RS256, WildFly Undertow, Node-RED Multi-Edge)
    *   Membre 3 : Rihem BEN SOUISSI (Frontend Back-Office Admin avec Assistant IA, PWA Front-Office, WebSocket & Web Push)

---

## 🧠 2. Usage des Assistants de Génération (IA)
*Cochez l'option qui correspond à votre réalité et détaillez le périmètre.*

*   [ ] **Option A :** Aucun outil de génération de code par IA n'a été utilisé.
*   [ ] **Option B :** Utilisation modérée (Aide au débogage, génération de fonctions isolées, documentation).
*   [x] **Option C :** Utilisation intensive (Génération de structures de dossiers, squelettes d'API complets, scripts de déploiement).

### 📝 Journal des invites (Prompts) notables et composants générés par IA (Démos 1, 2, 3, 4 & 5) :

| Composant / Module | Outil utilisé (ChatGPT/Claude/Gemini...) | Description du prompt ou du rôle confié à l'IA |
| :--- | :--- | :--- |
| *Persistance MongoDB & JNoSQL* | *Claude 3.5 Sonnet / Gemini* | *Configuration de jnosql-mongodb 1.1.x compatible avec WildFly 42 et Jakarta Data* |
| *WebSocket temps réel* | *Claude 3.5 Sonnet* | *Pattern découplé CDI (@Observes) avec EventBroadcaster pour diffuser les alertes* |
| *Agent IA Local & Function Calling* | *Claude 3.5 Sonnet* | *Intégration de LangChain4j avec Ollama (`llama3.2:3b`) et configuration des `@Tool`s (lecture capteurs, calcul de risque, plans d'évacuation)* |
| *Traçabilité LLMOps & Benchmark IA* | *Claude 3.5 Sonnet* | *Script de benchmark `check_ollama.py`, audit de latence/tokens et calibrage du timeout de l'agent à 60s* |
| *Serveur IAM OAuth 2.0 & OIDC* | *Claude 3.5 Sonnet * | *Conception de `OAuth2Resource.java` conforme RFC 7636 (PKCE S256), endpoints OIDC standardisés (`/.well-known/openid-configuration`, `/jwks`, `/authorize`, `/token`, `/revoke`), mire de login intégrée et rate-limiting* |
| *Cryptographie Asymétrique RS256* | *Claude 3.5 Sonnet * | *Génération du keystore PKCS12 (RSA 2048 bits), signature des access/id tokens avec `OidcKeyService.java` et exposition du JWKS (RFC 7517) avec empreinte SHA-256 (`kid`)* |
| *Validation Asymétrique Découplée API* | *Claude 3.5 Sonnet * | *Création de `TokenValidator.java` utilisant Nimbus JOSE `RemoteJWKSet` pour valider les tokens RS256 via le catalogue public JWKS, sans secret partagé* |
| *Hébergement Frontends WildFly Undertow* | *Claude 3.5 Sonnet * | *Configuration d'Undertow via les scripts CLI (`default-host.cli`, `add-host.cli`) pour servir les builds statiques Angular (Front Office & Back Office), réécriture d'URL SPA vers `index.html` et activation de `proxy-address-forwarding="true"`* |
| *Filtres CORS Dynamiques* | *Claude 3.5 Sonnet * | *Ajustement de `CorsFilter.java` dans `iam` et `api` pour gérer la variable `CORS_ORIGINS` et autoriser dynamiquement les domaines `.smartbuilding.local` et `.smartbuilding.me`* |
| *Tests Automatisés & Intégration* | *Claude 3.5 Sonnet * | *Conception des tests unitaires JUnit 5 / Mockito (`OAuth2ResourceTest.java`) et du script de test E2E PowerShell (`test-oauth-pkce.ps1`)* |
| *Passerelle Node-RED Multi-Edge* | *Claude 3.5 Sonnet* | *Conception du flux `flows.json` séparant le traitement Edge par étage (Étage 1 & Étage 2) et publication vers HiveMQ* |
| *Pipeline MLOps & Inférence* | *ChatGPT / Claude* | *Pipeline Scikit-learn (Random Forest) et script d'inférence en streaming `infer.py` calculant le risque en continu* |
| *Frontends Angular & PWA* | *Claude 3.5 Sonnet* | *Composants Angular (Building Map, Dashboard, Assistant IA conversationnel, gestion des formulaires et intercepteurs d'authentification)* |

---

## 📚 3. Emprunts, Squelettes de Cours & Plagiat
*Précisez si vous avez utilisé une base de code préexistante.*

*   **Code de départ enseignant / squelette du cours :**
    *   [x] Oui :
        - Squelette initial multi-modules Maven (`core`, `api`, `iam`) et entités NoSQL de départ.
        - Scripts d'automatisation JBoss CLI fournis par l'enseignant (`default-host.cli`, `add-host.cli`, `add-api.cli`).
    *   [ ] Non
*   **Réutilisation d'anciens projets (Années précédentes / GitHub externes) :**
    *   [ ] Oui
    *   [x] Non
    *   *Si Oui, listez les fichiers ou concepts importés directement :* Néant

---

## 🛠️ 4. Répartition Réelle de l'Écriture du Code
*Indiquez la provenance réelle (en %) des lignes de code actuellement sur votre branche principale.*

| Module / Composant | % Écrit à la main par l'équipe | % Généré par IA | % Emprunté / Réutilisé |
| :--- | :---: | :---: | :---: |
| **Passerelle IoT / Node-RED / Edge / MLOps** | 35 % | 65 % | 0 % |
| **Backend / API REST / WebSocket / LangChain4j** | 35 % | 65 % | 0 % |
| **Sécurité (IAM / OAuth2 PKCE / RS256 / JWKS / RBAC)** | 40 % | 60 % | 0 % |
| **Frontend / Back-Office & PWA Front-Office** | 45 % | 55 % | 0 % |
| **Infra / WildFly Undertow / Scripts CLI / Docker** | 30 % | 50 % | 20 % (Scripts prof) |

---

## ✍️ 5. Engagement sur l'Honneur
En validant ce fichier sur notre dépôt GitHub, nous certifions sur l'honneur que les déclarations ci-dessus sont exactes, sincères et reflètent fidèlement notre processus de développement. Nous sommes conscients que la falsification de ce document ou la non-maîtrise technique du code déclaré comme "écrit par l'équipe" lors des soutenances entraînera une **pénalité immédiate et sévère sur la note globale**.

*   [x] **Lu et approuvé par l'ensemble des membres de l'équipe.**
*   **Date de dernière mise à jour :** 23 / 11 / 2026
