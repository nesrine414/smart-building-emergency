# Pipeline CI/CD (étape 5, personne 1)

À chaque `push` sur `main`, GitHub Actions teste tout le projet, construit les WAR et les deux PWA,
puis les met en ligne sur le serveur. Si quelque chose ne démarre pas, la version précédente revient
toute seule et le pipeline passe au rouge.

| Fichier | Rôle |
| --- | --- |
| `.github/workflows/deploy.yml` | Le pipeline : 4 jobs (ci-dessous) |
| `.github/actionlint.yaml` | Déclare l'étiquette `sbe-server` pour le vérificateur `actionlint` |
| `deploy/ci/build-artifacts.sh` | Job « build » : rassemble `api.war`, `iam.war` et les builds Angular |
| `deploy/server/install-runner.sh` | Une fois, sur le serveur : installe le runner GitHub `sbe-server` |
| `deploy/server/deploy-app.sh` | Job « deploy », sur le serveur : met en ligne, contrôle, revient en arrière |
| `deploy/ci/war-casse/` | Fichier pour montrer au jury le retour automatique |
| `deploy/tests/test_deploy_app.py` | Tests de `deploy-app.sh` (faux WildFly), lancés par le pipeline |

## 1. Ce qui se passe à chaque push

```
pull_request ─┐
push main ────┼─> Tests Java (mvn verify) ──┐
manuel ───────┘   Tests Python (pytest) ────┴─> Build (WAR + PWA) ─> Déploiement (push/manuel sur main)
                  ── machines de GitHub ──────────────────────────    ── runner sur le serveur ──
```

1. **Tests**, en parallèle : `mvn -B -ntp verify` (JDK 21) et `pytest` (Python 3.12, versions de
   l'image `sbe-ml`) sur `infra/mqtt/tests`, `iot/mlops/tests`, `iot/llm/tests`, `deploy/tests`.
   Un test rouge arrête tout : rien n'est construit ni déployé.
2. **Build** : `mvn -DskipTests package` (déjà testé), puis `npm ci` et
   `npm run build -- --configuration production` pour `client-pwa` et `admin-pwa` (Node 22).
   Artefact `sbe-app` gardé 7 jours (onglet Actions > l'exécution > Artifacts).
3. **Déploiement**, seulement pour `main` (jamais pour une pull request), sur le serveur lui-même :
   `deploy-app.sh` copie la version dans `/opt/sbe/releases/<date>-<commit>/`, photographie ce qui
   est en ligne, avance le dépôt du serveur au commit, reconstruit `sbe-ml` si `iot/mlops/` ou un
   fichier compose a changé, remplace les PWA dans `/var/opt/wildfly/frontoffice` et `backoffice`,
   dépose `iam.war` puis `api.war` dans le dossier du scanner de WildFly et attend `.deployed`.
4. **Contrôles** (jusqu'à 2 minutes) : `www` et `admin` répondent par leur `index.html`, la découverte
   OIDC de l'IAM répond (`"issuer"`), `sbe-ml` est `healthy`. Les contrôles passent par
   `http://127.0.0.1:8080` avec le bon en-tête `Host` : ils vérifient WildFly lui-même, même avant
   les certificats Let's Encrypt. En cas d'échec : retour automatique (section 5).

Deux déploiements ne se chevauchent jamais (groupe `deploy-sbe-server` et verrou `flock`).

## 2. Mise en place (une fois)

**a. Dépôt privé.** GitHub > Settings > General > Danger Zone > Change visibility > Private.
`install-runner.sh` refuse un dépôt public (section 6).

**b. Règles de `main`** (Settings > Branches > Add rule, ou Rules > Rulesets) : pull request
obligatoire avec une relecture, et contrôles obligatoires « Tests Java (mvn verify) »,
« Tests Python (pytest) », « Build (WAR + PWA) ». Sur un dépôt privé, il faut un compte GitHub Pro :
il est inclus dans le GitHub Student Developer Pack du propriétaire du dépôt. Sans lui, la règle
reste écrite dans `docs/CONTRIBUTING.md` et s'applique à la main.

**c. Serveur** (en `ubuntu`, puis en `sbe`) :

```bash
# Dossiers de l'étape 5 (/opt/sbe/releases, /var/opt/wildfly/frontoffice et backoffice) :
# relancer bootstrap.sh suffit, il ne refait que ce qui manque.
sudo bash /opt/sbe/smart-building-emergency/deploy/server/bootstrap.sh

# Le dépôt du serveur doit pouvoir faire « git fetch » seul (clé de déploiement de la démo 4) :
cd /opt/sbe/smart-building-emergency
git config core.sshCommand "ssh -i ~/.ssh/sbe_deploy -o IdentitiesOnly=yes"
git fetch && git status          # doit répondre sans mot de passe, sans fichier modifié
```

**d. Droits sur WildFly (avec la personne 2).** `sbe` doit pouvoir écrire dans
`standalone/deployments` :

```bash
# Si WildFly tourne en sbe : rien à faire. Sinon (utilisateur wildfly) :
sudo chgrp sbe /opt/wildfly/standalone/deployments
sudo chmod 2775 /opt/wildfly/standalone/deployments
```

WildFly doit pouvoir **lire** `/var/opt/wildfly/*` (droits 755, déjà mis par `bootstrap.sh`).
Si `api.war` ou `iam.war` a été déployé à la main par `jboss-cli` (`deploy …`), le retirer une fois
(`undeploy api.war`) : sinon le scanner tombe sur un doublon et le déploiement échoue.
WildFly installé ailleurs que `/opt/wildfly` : `WILDFLY_HOME=…` dans `/opt/sbe/deploy.env`.

**e. Runner GitHub sur le serveur.** Settings > Actions > Runners > New self-hosted runner > Linux :
copier seulement le **jeton** affiché (valable 1 heure), puis :

```bash
sudo bash /opt/sbe/smart-building-emergency/deploy/server/install-runner.sh https://github.com/<compte>/<dépôt>
```

Le script vérifie que le dépôt est privé, télécharge la dernière version du runner (empreinte SHA-256
comparée à celle publiée par GitHub), l'inscrit sous le nom et l'étiquette `sbe-server`, et l'installe
comme service systemd en `sbe`. **Terminé quand** le runner est « Idle » dans GitHub, et le redevient
seul après `sudo reboot`.

**f. Allumer le déploiement.** Tant que le serveur n'existe pas, le job « Déploiement » est
« skipped » : tests et build tournent quand même à chaque pull request et à chaque push. Une fois
le runner « Idle » : GitHub > Settings > Secrets and variables > Actions > onglet **Variables** >
New repository variable : nom `SBE_DEPLOY_ENABLED`, valeur `true`. Pour couper les déploiements
(serveur arrêté, maintenance), mettre `false`.

**g. Premier essai** : un petit commit sur `main` par une pull request, puis onglet Actions. Les
quatre jobs passent au vert en moins de 10 minutes ; `bash deploy/server/deploy-app.sh --list` sur le
serveur montre la version `ok   <- en ligne`.

## 3. Réglages facultatifs : `/opt/sbe/deploy.env`

Aucun secret dans ce fichier (il est lu par `deploy-app.sh`, pas par Docker). Exemple :

```bash
WILDFLY_HOME=/opt/wildfly
# Contrôles HTTP : entrées « URL|Host|texte attendu », séparées par des espaces.
# Vide = www, admin et la découverte OIDC sur 127.0.0.1:8080 (domaine lu dans /opt/sbe/.env).
# Tant que l'IAM n'a pas sa découverte OIDC, garder seulement les PWA :
SMOKE_URLS="http://127.0.0.1:8080/|www.sbe-team.me|<html http://127.0.0.1:8080/|admin.sbe-team.me|<html"
# Une fois Let's Encrypt en place, on peut ajouter le chemin public complet :
# SMOKE_URLS="$SMOKE_URLS https://www.sbe-team.me/||<html"
```

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `WILDFLY_HOME` / `DEPLOYMENTS_DIR` | `/opt/wildfly` / `…/standalone/deployments` | Dossier du scanner |
| `WEB_ROOT` | `/var/opt/wildfly` | Dossier des deux PWA |
| `SMOKE_URLS` | (www, admin, découverte OIDC) | Contrôles HTTP après déploiement |
| `CHECK_SBE_ML` | `auto` | `1` : `sbe-ml` healthy obligatoire ; `0` : pas contrôlé |
| `DEPLOY_TIMEOUT` / `SMOKE_TIMEOUT` / `SBE_ML_TIMEOUT` | 300 / 120 / 180 s | Délais |
| `KEEP_RELEASES` | 5 | Versions gardées dans `/opt/sbe/releases` |

## 4. Les versions sur le serveur

```
/opt/sbe/releases/
  CURRENT                         nom de la version en ligne
  20261118T101500Z-3f2c1ab/
    artifacts/                    ce que le pipeline a livré (wars/, frontoffice/, backoffice/, BUILD_INFO)
    before/                       photo de ce qui était en ligne juste avant
    sha  status  deploy.log       commit, état (ok, echec-retour-fait, echec-retour-incomplet), journal
```

## 5. Retour arrière

- **Automatique** : WAR refusé par WildFly (`api.war.failed`), WAR pas démarré dans le délai, contrôle
  HTTP en échec, `sbe-ml` pas `healthy`, dépôt du serveur modifié à la main. `deploy-app.sh` remet
  le dépôt au commit d'avant (et reconstruit `sbe-ml` s'il l'avait reconstruit), les PWA d'avant, les
  WAR d'avant, refait les contrôles, puis sort en erreur : le job est rouge, le résumé de l'exécution
  donne l'état et le chemin du journal.
- **À la main** (en `sbe`) : `bash deploy/server/deploy-app.sh --rollback` remet en ligne la version
  réussie précédente (deux `--rollback` de suite reculent de deux versions). `--list` montre l'historique.
- Un retour ne change pas `main` : corriger par une nouvelle pull request (`git revert`).

**Montrer au jury le retour automatique** (5 minutes) :

```bash
git switch -c demo/war-casse
cp deploy/ci/war-casse/jboss-deployment-structure.xml api/src/main/webapp/WEB-INF/
git commit -am "test(ci): WAR volontairement cassé" && git push -u origin demo/war-casse
# pull request -> tests verts -> fusion : le job deploy passe au rouge, le site reste sur l'ancienne version
git switch main && git pull && git revert --no-edit HEAD && git push   # (par une pull request si main est protégée)
```

## 6. Sécurité

- **Dépôt privé** tant que le runner existe : sur un dépôt public, une pull request venant d'un fork
  pourrait modifier le workflow et faire tourner son code sur le serveur. GitHub le déconseille
  pour les runners auto-hébergés.
- Le job `deploy` ne tourne que pour `main`, jamais pour une pull request.
- Aucun secret dans GitHub : le runner est déjà sur le serveur. Les secrets restent dans
  `/opt/sbe/.env` (`deploy-app.sh` n'y lit que `PUBLIC_DOMAIN`). `permissions: contents: read` et
  `persist-credentials: false` : le jeton GitHub du job ne peut rien écrire.
- Aucun port ouvert pour GitHub : le runner sort en HTTPS (443) vers GitHub. Le port 22 reste
  limité aux IP de l'équipe.
- Le runner tourne en `sbe`, pas en root. `sbe` est dans le groupe `docker`, ce qui revient presque à
  root sur cette machine : d'où les règles de `main` (relecture + tests) avant toute fusion.

## 7. Dépannage

| Message | Cause, solution |
| --- | --- |
| Job deploy « Waiting for a runner » | Runner arrêté : `sudo systemctl status 'actions.runner.*'`, ou serveur éteint |
| Job deploy « skipped » sur main | Variable `SBE_DEPLOY_ENABLED` absente ou différente de `true` (section 2.f) |
| `non modifiable par sbe` | Droits de `standalone/deployments` (section 2.d) |
| `git fetch origin impossible` | Clé de déploiement (section 2.c) |
| `fichiers modifiés à la main` | `git -C /opt/sbe/smart-building-emergency status` ; `git stash` ou `git checkout -- <fichier>` |
| `ne suit pas … pas d'avance rapide` | Historique de `main` réécrit (`push --force`) : à éviter ; sinon `git reset --hard origin/main` sur le serveur |
| `WildFly refuse api.war` | Le journal donne l'erreur de WildFly ; détails dans `standalone/log/server.log` |
| `pas de marqueur .deployed` | WildFly arrêté, ou scanner désactivé dans `standalone.xml` |
| `ÉCHEC http://127.0.0.1:8080/…` | Hôte virtuel Undertow (personne 2), ou `SMOKE_URLS` à ajuster (section 3) |
| `npm ci … package-lock.json … in sync` | Lancer `npm install` dans la PWA concernée et pousser le `package-lock.json` |

## 8. Vérifié avant livraison

- `actionlint` 1.7.12 (avec ShellCheck) : aucun problème sur `deploy.yml`. Versions des actions
  (`checkout@v7`, `setup-java@v6`, `setup-python@v7`, `setup-node@v7`, `upload-artifact@v7`,
  `download-artifact@v8`) relevées sur leurs dépôts officiels.
- ShellCheck sans remarque sur les trois scripts.
- `deploy-app.sh` : 8 scénarios automatiques avec un vrai Git et un faux WildFly qui imite le scanner
  et les hôtes virtuels (`deploy/tests/test_deploy_app.py`, 13 tests avec ceux de la démo 4) ; refus
  propre quand `sbe` ne peut pas écrire dans `deployments`.
- `build-artifacts.sh` avec les vraies PWA (Angular 21, Node 22) : build de production des deux PWA
  et assemblage réussis, une fois les `package-lock.json` resynchronisés (voir LISEZMOI).
- `install-runner.sh` : enchaînement inscription / service / relance / `--replace` avec un faux runner,
  lecture de l'empreinte SHA-256 dans les notes de version.

Pas vérifiable ici : une vraie exécution GitHub Actions, l'inscription d'un vrai runner, Maven (pas
d'accès au dépôt Maven Central depuis le poste de préparation), un vrai WildFly. À suivre des yeux au
premier passage (section 2.g).
