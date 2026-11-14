# MLOps : score de risque d'incendie (étape 3, personne 1)

Un modèle entraîné sur un dataset public calcule, pour chaque salle, un score de risque entre 0 et 1,
publié en direct sur MQTT (contrat 6). C'est une **aide à la décision** : il ne déclenche ni incident
ni actionneur, les règles de l'étape 2 restent seules à le faire.

```
simulateur / ESP32 --GAS, SMOKE, TEMPERATURE--> HiveMQ --> infer.py (compte sbe-ml)
                                                              | conversion d'unités + modèle vN
backend (MongoDB, WebSocket, agent IA) <--RISK-- HiveMQ <-----+
```

## Fichiers

| Fichier | Rôle |
| --- | --- |
| `features.py` | Colonnes du dataset, variables du modèle, conversion de nos capteurs, scénarios de contrôle |
| `train.py` | Nettoyage, découpage par blocs de temps, entraînement, comparaisons, versions |
| `infer.py` | Abonnement MQTT, dernières valeurs par salle, publication du score `RISK` |
| `tests/` | 21 tests pytest sur un dataset synthétique aux mêmes colonnes (aucun téléchargement) |
| `data/` | Le CSV du dataset (pas sur Git) |
| `models/` | `model-vN.joblib` (pas sur Git), `metrics-vN.json` et `registry.json` (sur Git : traçabilité) |

## 1. Installer

```powershell
cd iot\mlops
python -m venv .venv
.venv\Scripts\activate            # Linux/macOS : source .venv/bin/activate
pip install -r requirements.txt
python -m pytest -q tests         # 21 passed
```

## 2. Télécharger le dataset

- **Smoke Detection Dataset**, Kaggle : https://www.kaggle.com/datasets/deepcontractor/smoke-detection-dataset
- Fichier : `smoke_detection_iot.csv` (62 630 lignes, 16 colonnes), à placer dans `iot/mlops/data/`.
- Téléchargé le : **à compléter** (date du téléchargement).
- Empreinte SHA-256 du fichier utilisé pour les résultats ci-dessous :
  `7bbf5deaab4c94746b344cee7bfec493f69b7d624ea63f35fabcd3c4a4e6eb7d`.
  Vérifier : `Get-FileHash data\smoke_detection_iot.csv` (PowerShell) ou `sha256sum`. `train.py`
  enregistre aussi cette empreinte dans chaque `metrics-vN.json`.

Le CSV n'est pas poussé sur Git (`.gitignore`) : chacun le télécharge.

## 3. Correspondance entre nos capteurs et le dataset

| Notre capteur | Colonne du dataset | Conversion | Dans le modèle |
| --- | --- | --- | --- |
| `TEMPERATURE` (°C) | `Temperature[C]` | identité, bornée à la plage du dataset | oui |
| `GAS` (MQ-135, ppm) | `TVOC[ppb]` et `eCO2[ppm]` | points d'ancrage (ci-dessous) | oui |
| `SMOKE` (ppm) | `PM2.5` | points d'ancrage | oui |
| `HUMIDITY` (%) | `Humidity[%]` | identité | **non** |
| `MOTION` | aucune | le dataset n'a pas de présence | non (règles) |
| étiquette | `Fire Alarm` (0 ou 1) | | cible |

**Pourquoi pas l'humidité ?** Dans le dataset, elle sépare très bien les classes (entre 40,7 et 46,5 %,
2 % d'alarmes seulement), mais c'est un effet des lieux d'enregistrement, pas du feu. Le simulateur
tire l'humidité au hasard entre 30 et 60 % : le score sauterait au gré de ce hasard.

**Conversion par points d'ancrage.** Le MQ-135 donne des « ppm » approximatifs, le dataset mesure le
TVOC en ppb : aucune formule exacte n'existe. On aligne donc des niveaux qui ont le même sens des
deux côtés, puis on interpole entre eux :

| Niveau | GAS (ppm) | SMOKE (ppm) | Valeur du dataset visée |
| --- | --- | --- | --- |
| haut de notre plage normale | 400 | 50 | médiane des lignes **sans** alarme |
| seuil d'alerte de nos règles | 1000 | 300 | médiane des lignes **avec** alarme |
| seuil HIGH de nos règles | 1500 | 600 | 95e centile des lignes avec alarme |

Avec le fichier ci-dessus : GAS 400 → TVOC 89 ppb, GAS 1000 → 1094 ppb, GAS 1500 → 1375 ppb ;
SMOKE 300 → PM2.5 1,93. Ces points sont recalculés à chaque entraînement (sur la partie entraînement
seulement) et enregistrés dans le modèle et dans `metrics-vN.json` : `infer.py` applique exactement la
conversion validée. La conversion se fait dans `infer.py`, pas dans le simulateur, pour ne pas changer
les seuils des règles de l'étape 2.

## 4. Entraîner

```powershell
python train.py --notes "v1 : RandomForest monotone, blocs de 10 min"
```

Ce que fait `train.py`, et pourquoi :

1. **Doublons retirés.** Le fichier Kaggle contient des sessions enregistrées deux fois : mêmes
   valeurs sur toutes les colonnes, seules l'heure et la température changent. 30 738 lignes sur
   62 630 sont des copies ; on garde 31 892 lignes.
2. **Découpage par blocs de temps.** Des blocs de 10 minutes ; un sur cinq va au test (20 %), et
   60 secondes sont retirées de l'entraînement autour de chaque bloc de test. Les lignes voisines,
   prises à une seconde d'écart, sont presque identiques : un découpage au hasard en mettrait des
   copies des deux côtés.
3. **RandomForest avec contrainte de monotonie** (`monotonic_cst`, scikit-learn ≥ 1.4) : plus de
   gaz, de fumée ou de chaleur ne peut jamais faire baisser le risque. Sans cette contrainte, la forêt
   apprend des particularités du dataset (au-dessus de 27 °C, presque aucune alarme) et dit
   « chaud = sûr ». `random_state=42` : deux entraînements donnent exactement les mêmes chiffres.
4. **Comparaisons** enregistrées dans les métriques (voir le tableau ci-dessous).
5. **Contrôle de cohérence sur nos scénarios** : le modèle doit donner moins de 0,5 à une salle
   normale, et au moins 0,2 de plus à une fuite de gaz ou un incendie. Sinon la version est
   enregistrée mais **pas promue**.
6. **Versions** : `models/model-vN.joblib`, `models/metrics-vN.json`, et une entrée dans
   `models/registry.json`. Rien n'est jamais écrasé ; `registry.json` indique la version `current`
   utilisée par `infer.py`.

### Résultats de la version 1 (fichier ci-dessus)

Test par blocs de temps : 6 000 lignes, dont 4 200 alarmes.

| Modèle | Précision | Rappel | F1 | AUC | Nos scénarios |
| --- | --- | --- | --- | --- | --- |
| **RandomForest monotone (publié)** | **0,878** | **0,856** | **0,867** | **0,906** | **réussi** |
| Référence « toujours alarme » | 0,700 | 1,000 | 0,824 | | |
| Forêt sans contrainte de monotonie | 0,988 | 1,000 | 0,994 | 1,000 | échoué |
| Approche naïve (doublons, hasard, sans contrainte) | 0,996 | 0,989 | 0,992 | 1,000 | échoué |
| IsolationForest (sans étiquette) | 0,889 | 1,000 | 0,941 | 0,801 | (score non borné) |

Matrice de confusion du modèle publié : 1 300 vrais négatifs, 500 fausses alarmes, 603 alarmes
manquées, 3 597 alarmes détectées.

Risque prédit sur nos scénarios : salle normale 0,06 à 0,25 ; début de fuite de gaz (1 000 ppm)
0,57 ; pic de fuite (2 000 ppm) 0,63 ; incendie en cours 0,68 ; pic d'incendie 0,72. La forêt sans
contrainte, elle, donne 0,17 au pic d'incendie : excellente sur le dataset, inutilisable chez nous.

**Ce qu'il faut en dire à la démo.** Le dataset a 70 % d'alarmes : répondre toujours « alarme » donne
déjà un F1 de 0,824, d'où l'intérêt de l'AUC (0,906). Les modèles à 0,99 apprennent les conditions de
chaque session d'enregistrement, pas le feu ; nous avons accepté un F1 plus bas pour un modèle qui
réagit dans le bon sens sur nos capteurs. Sur ce fichier, le découpage par blocs change peu le score
du modèle monotone (0,867 dans les deux cas) : c'est la bonne pratique pour des séries temporelles,
et elle ne coûte rien.

L'IsolationForest, appris sans étiquette sur l'air normal, détecte bien les alarmes du dataset, mais
son score n'a pas de borne fixe et compte aussi comme anormal ce qui est trop bas (air très pur) :
il n'est pas publié comme `RISK`. Ses scores sur nos scénarios sont dans `metrics-v1.json`.

Testé avec scikit-learn 1.5.2 et 1.9.1 : mêmes métriques de test pour le modèle publié, risques
des scénarios identiques à 0,01 près.

## 5. Publier le score en direct

Le broker, les certificats et le `.env` doivent être prêts (voir `infra/mqtt/README.md`).

```powershell
python infer.py --tls                 # compte sbe-ml, mot de passe MQTT_ML_PASSWORD lu dans le .env
python infer.py --tls --dry-run       # calcule et affiche sans publier
python infer.py --model-version 1     # une version précise
```

Pour chaque salle, `infer.py` garde la médiane des 3 dernières mesures reçues depuis moins de 30 s,
attend d'avoir les trois capteurs (ou 5 s au plus), puis publie au plus un score toutes les 5 s :

```
smartbuilding/bld-01/room-101/RISK
{"sensorId": "ml-101", "type": "RISK", "value": 0.632, "unit": "score", "timestamp": 1762940000000}
```

Un capteur absent est remplacé par une valeur neutre (haut de la plage normale, ou 22 °C) et
signalé dans la console. Sans gaz ni fumée, aucun score n'est publié.

## 6. Vérifier le critère de la démo

```powershell
python iot\simulator\simulator.py --tls --rooms 201,101,102 --scenario fire   # incendie en salle 201
```

Dans la console d'`infer.py`, le score de `room-201` passe d'environ 0,25 à 0,7 en une minute environ
(les 30 cycles de montée du simulateur). Côté backend : `GET /api/readings` contient des mesures `type: RISK` pour `room-201`.
Le scénario touche toujours la **première** salle de `--rooms` : sans option, c'est la salle 101.

## Réentraîner (cycle MLOps)

Changer un réglage (`--block-minutes`, `--test-every`) ou les ancrages de `features.py`, relancer
`train.py` avec `--notes`, comparer les `metrics-vN.json`. Une version qui échoue au contrôle de
cohérence n'est pas utilisée. Pousser `metrics-vN.json` et `registry.json`, pas les `.joblib` : sur un
autre PC, relancer `train.py` (même dataset + même `random_state` = mêmes chiffres).
