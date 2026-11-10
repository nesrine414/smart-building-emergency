# 📜 Déclaration d'Attribution et d'Intégrité Académique
### Module : Cloud of Things (COT) · Année 2026/2027 · SUP'COM

Ce document est un **livrable obligatoire** devant être complété et mis à jour à chaque sprint par l'équipe. Il vise à cartographier de manière transparente l'origine du code, des architectures et l'usage des outils d'assistance (IA, bibliothèques tiers, codes d'archive).

---

## 👥 1. Identification de l'Équipe
*   **Projet (Nom) :** Smart Building Emergency
*   **Équipe (Numéro) :** 7
*   **Membres :**
    *   Membre 1  : Inès BESBES (Tuyau MQTT & Simulateur Python)
    *   Membre 2  : Nesrine HAMROUNI (Backend WildFly, Jakarta Data, MongoDB, API REST, WebSocket, WoT)
    *   Membre 3  : Rihem BEN SOUISSI (Frontend PWA Angular, Visualisation LBS, Web Push)

---

## 🧠 2. Usage des Assistants de Génération (IA)

*   [ ] **Option A :** Aucun outil de génération de code par IA n'a été utilisé.
*   [] **Option B :** Utilisation modérée (Aide au débogage, génération de fonctions isolées, documentation).
*   [x] **Option C :** Utilisation intensive (Génération de structures de dossiers, squelettes d'API complets, scripts de déploiement).

### 📝 Journal des invites (Prompts) notables et composants générés par IA :

| Composant / Module | Outil utilisé (ChatGPT/Claude/Copilot...) | Description du prompt ou du rôle confié à l'IA |
| :--- | :--- | :--- |
| *Persistance MongoDB & JNoSQL* | *Claude 3.5 Sonnet / Gemini* | *Aide à la configuration des versions jnosql-mongodb 1.1.x compatibles avec WildFly 42 et Jakarta Data* |
| *WebSocket temps réel* | *Claude 3.5 Sonnet* | *Mise en place du pattern découplé CDI (@Observes) avec EventBroadcaster pour diffuser les événements* |
| *Débogage WildFly & Java 25* | *Claude 3.5 Sonnet* | *Résolution des erreurs UnsupportedClassVersionError (Java 17 vs Java 21/25) et configuration JAVA_HOME* |

---

## 📚 3. Emprunts, Squelettes de Cours & Plagiat

*   **Code de départ enseignant / squelette du cours :**
    *   [x] Oui (squelette initial multi-modules Maven : `core`, `api`, `iam` et entités NoSQL de départ)
    *   [ ] Non
*   **Réutilisation d'anciens projets (Années précédentes / GitHub externes) :**
    *   [ ] Oui
    *   [x] Non
    *   *Si Oui, listez les fichiers ou concepts importés directement :* Néant

---

## 🛠️ 4. Répartition Réelle de l'Écriture du Code

| Module / Composant | % Écrit à la main par l'équipe | % Généré par IA | % Emprunté / Réutilisé |
| :--- | :---: | :---: | :---: |
| **Passerelle IoT / MQTT / Simulateur Python** | 30 % | 70 % | 0 % |
| **Backend / API REST / WebSocket / Jakarta Data** | 30 % | 70 % | 0 % |
| **Sécurité (IAM / VAPID Keys / CORS)** | 50 % | 50 % | 0 % |
| **Frontend / PWA Angular** | 50 % | 50 % | 0 % |
| **Scripts d'Infra / Docker Compose** | 20 % | 80 % | 0 % |

---

## ✍️ 5. Engagement sur l'Honneur
En validant ce fichier sur notre dépôt GitHub, nous certifions sur l'honneur que les déclarations ci-dessus sont exactes, sincères et reflètent fidèlement notre processus de développement. Nous sommes conscients que la falsification de ce document ou la non-maîtrise technique du code déclaré comme "écrit par l'équipe" lors des soutenances entraînera une **pénalité immédiate et sévère sur la note globale**.

*   [x] **Lu et approuvé par l'ensemble des membres de l'équipe.**
*   **Date de dernière mise à jour :** 10 / 11 / 2026