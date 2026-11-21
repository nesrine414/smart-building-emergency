# Mise en ligne : serveur, broker MQTT et score ML (étape 4, personne 1)

Ce dossier met en ligne la partie de la personne 1 sur un serveur AWS : le socle du serveur
(utilisé aussi par les personnes 2 et 3), le broker HiveMQ sur `mqtt.<domaine>:8883` et le service
`sbe-ml`. Les scripts de la démo en un clic sont dans `demo/`.

| Fichier | Où le lancer | Rôle |
| --- | --- | --- |
| `server/bootstrap.sh` | serveur, en root, une fois | Docker, JDK 21, Python, Nginx, certbot, utilisateur `sbe`, swap, SSH par clé, ufw |
| `server/deploy-broker-ml.sh` | serveur, en `sbe` | Extension RBAC, certificats avec `mqtt.<domaine>`, comptes, modèle, démarrage, contrôle |
| `compose.sh` | serveur | `docker compose` avec les trois fichiers (`docker-compose.yml`, `.secure.yml`, `.prod.yml`) |
| `fetch-ca.ps1` / `.sh` | votre PC | Copie le `ca.pem` du serveur dans `infra/mqtt/certs-serveur/` et affiche son empreinte |
| `check_ports.py` | votre PC | Les 5 noms pointent vers l'IP fixe, 80/443/8883 répondent, les ports internes sont fermés |
| `../docker-compose.prod.yml` | serveur | MongoDB sur `127.0.0.1` avec mot de passe, HiveMQ en TLS seul, service `sbe-ml` |
| `../demo/` | PC de soutenance | `incendie`, `fuite-gaz`, `retour-normal` (`.cmd` à double-cliquer), plan B par SSH |
| `server/install-runner.sh` | serveur, avec sudo, une fois | Runner GitHub Actions `sbe-server` du pipeline (étape 5) |
| `server/deploy-app.sh` | serveur, par le pipeline | Mise en ligne des WAR et des PWA, contrôles, retour arrière (étape 5) |
| `ci/build-artifacts.sh` | machines de GitHub | Assemble `api.war`, `iam.war` et les builds Angular (étape 5) |

**Étape 5 : le pipeline CI/CD** (tests, build et mise en ligne à chaque push sur `main`) est décrit
dans [`CI-CD.md`](CI-CD.md).

## 1. Compte AWS (console, une fois)

1. Créer le compte en choisissant le **Free plan** (crédits offerts aux nouveaux comptes, 6 mois au plus).
   Dans **Billing > Budgets**, créer une alerte e-mail à 5 USD.
2. Choisir la région **Europe (Paris) `eu-west-3`** (ou Milan `eu-south-1`) en haut à droite, et la garder.
3. **EC2 > Key pairs > Create key pair** : nom `sbe-cle`, format `.pem`. Le fichier téléchargé est la
   seule clé du serveur : le garder hors du dépôt, ne jamais le pousser sur Git.
4. **EC2 > Security groups > Create** `sbe-web`, règles entrantes :

   | Type | Port | Source |
   | --- | --- | --- |
   | SSH | 22 | My IP (ajouter l'IP de chaque membre, et celle de l'école si besoin) |
   | HTTP | 80 | 0.0.0.0/0 |
   | HTTPS | 443 | 0.0.0.0/0 |
   | Custom TCP | 8883 | 0.0.0.0/0 |

   Rien d'autre : ni 1883, ni 8080, ni 9990, ni 27017, ni 11434.
5. **EC2 > Launch instance** : Ubuntu Server 24.04 LTS, type `m7i-flex.large` (2 vCPU, 8 Go, listé dans
   le Free plan), key pair `sbe-cle`, security group `sbe-web`, disque `gp3` de 30 Go.
6. **EC2 > Elastic IPs > Allocate**, puis **Associate** avec l'instance : l'IP ne change plus quand
   on arrête l'instance.

## 2. Domaine et DNS (Namecheap, une fois)

1. Réserver le domaine `.me` avec l'offre du GitHub Student Developer Pack.
2. **Domain List > Manage > Advanced DNS** : supprimer les enregistrements de parking (`CNAME www`,
   `URL Redirect @`), puis ajouter cinq **A Record**, valeur = l'Elastic IP, TTL 5 min :
   `www`, `admin`, `api`, `iam`, `mqtt`.
3. Au moins 48 heures avant la soutenance. Vérifier : `nslookup mqtt.<domaine>`.

## 3. Préparer le serveur (une fois)

Depuis votre PC, à la racine du dépôt (PowerShell ou bash) :

```powershell
scp -i sbe-cle.pem deploy/server/bootstrap.sh ubuntu@<IP>:/tmp/
ssh -i sbe-cle.pem ubuntu@<IP> "sudo bash /tmp/bootstrap.sh"
```

Ensuite, on se connecte toujours en `sbe` : `ssh -i sbe-cle.pem sbe@<IP>`.
Sous Windows, si `ssh` refuse la clé (« UNPROTECTED PRIVATE KEY FILE ») : clic droit sur le `.pem` >
Propriétés > Sécurité > Avancé, désactiver l'héritage et ne laisser que votre compte.

## 4. Mettre en ligne le broker et `sbe-ml`

1. Sur votre PC, compléter le `.env` de l'équipe : `PUBLIC_DOMAIN`, `MQTT_BROKER_PUBLIC_HOST=mqtt.<domaine>`,
   `SBE_SSH_TARGET=sbe@<IP>`, `SBE_SSH_KEY=<chemin du .pem>`. `python infra/mqtt/gen_credentials.py --init-env`
   remplit les secrets vides, dont `MONGO_ROOT_PASSWORD`.
2. Cloner le dépôt sur le serveur, dans `/opt/sbe` :

   ```bash
   ssh -i sbe-cle.pem sbe@<IP>
   cd /opt/sbe && git clone <URL du dépôt> smart-building-emergency
   ```

   Dépôt privé : sur le serveur, `ssh-keygen -t ed25519 -f ~/.ssh/sbe_deploy -N ""`, ajouter
   `~/.ssh/sbe_deploy.pub` dans GitHub > dépôt > Settings > Deploy keys (lecture seule), puis cloner
   avec `GIT_SSH_COMMAND="ssh -i ~/.ssh/sbe_deploy" git clone git@github.com:<compte>/<dépôt>.git smart-building-emergency`.
   Aucun mot de passe personnel sur le serveur.
3. Copier les fichiers qui ne passent pas par Git :

   ```powershell
   scp -i sbe-cle.pem .env sbe@<IP>:/opt/sbe/.env
   scp -i sbe-cle.pem iot/mlops/data/smoke_detection_iot.csv sbe@<IP>:/opt/sbe/smart-building-emergency/iot/mlops/data/
   ```

4. Sur le serveur :

   ```bash
   cd /opt/sbe/smart-building-emergency
   bash deploy/server/deploy-broker-ml.sh
   ```

   Le script relie `.env` à `/opt/sbe/.env` (contrat 6), installe l'extension RBAC, génère les
   certificats avec `mqtt.<domaine>`, les comptes MQTT, entraîne le modèle **dans l'image `sbe-ml`**,
   écrit `/opt/sbe/wildfly-mqtt.env` pour WildFly, démarre MongoDB, HiveMQ et `sbe-ml`, attend l'état
   `healthy`, puis lance `check_mqtt_security.py`. Il affiche l'empreinte SHA-256 de la CA.

## 5. Vérifier depuis votre PC

```powershell
powershell -ExecutionPolicy Bypass -File deploy\fetch-ca.ps1      # même empreinte que sur le serveur
python deploy\check_ports.py <domaine>
python infra\mqtt\check_mqtt_security.py --host mqtt.<domaine> --cafile infra\mqtt\certs-serveur\ca.pem
powershell -ExecutionPolicy Bypass -File infra\mqtt\tls-report.ps1  # rapport HTML (Docker Desktop)
```

## 6. Au quotidien

- **Arrêter l'instance** en fin de séance (EC2 > Instance state > Stop) : elle consomme des crédits.
  La démarrer une heure avant la soutenance. Docker, HiveMQ, MongoDB et `sbe-ml` repartent seuls.
- **Mettre à jour** : depuis l'étape 5, le pipeline le fait à chaque push sur `main` (`CI-CD.md`).
  Le broker reste à part : après un changement dans `infra/mqtt/`, `bash deploy/server/deploy-broker-ml.sh`
  (le modèle n'est réentraîné que s'il manque).
- **Journaux** : `bash deploy/compose.sh logs -f sbe-ml` (ou `hivemq`) ; état : `bash deploy/compose.sh ps`.
- **Nouveau modèle** : `bash deploy/compose.sh run --rm sbe-ml python train.py --notes "..."`, puis
  `bash deploy/compose.sh restart sbe-ml`.

## Pour les personnes 2 et 3

- **WildFly (personne 2)** : ajouter `EnvironmentFile=/opt/sbe/wildfly-mqtt.env` au service systemd
  (URL `ssl://localhost:8883`, compte `sbe-backend`, truststore). MongoDB :
  `mongodb://<MONGO_ROOT_USER>:<MONGO_ROOT_PASSWORD>@localhost:27017/?authSource=admin`.
  Le compte MongoDB n'est créé qu'avec un volume vide : sur un serveur neuf, c'est le cas.
- **Nginx (personne 3)** : Nginx et certbot sont installés et démarrés par `bootstrap.sh` ; leur
  configuration reste à faire. Le port 8883 ne passe pas par Nginx.

## Vérifié avant livraison

`docker-compose.prod.yml` fusionné avec `docker compose config` (ports, volumes, `!override`).
`deploy-broker-ml.sh` exécuté en entier avec un utilisateur non root, un faux `docker` (pas de démon
Docker sur le poste de préparation) et un vrai HiveMQ : lien `.env`, extension, certificats,
entraînement sous Python 3.12 avec les versions de l'image, `wildfly-mqtt.env`, contrôle MQTT réussi ;
deuxième passage sans rien refaire. `bootstrap.sh` n'a pas pu être lancé sur une vraie Ubuntu 24.04 :
vérifié par ShellCheck seulement, à suivre des yeux au premier lancement.
