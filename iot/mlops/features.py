"""
Définitions partagées par train.py et infer.py : colonnes du dataset, variables du modèle et
conversion de nos capteurs vers les unités du dataset.

Dataset : Smoke Detection Dataset (Kaggle, deepcontractor), fichier smoke_detection_iot.csv.

Correspondance retenue (voir README.md pour la justification chiffrée) :

    Notre capteur        Colonne du dataset   Conversion
    TEMPERATURE (°C)     Temperature[C]       identité (même unité), bornée à la plage du dataset
    GAS (MQ-135, ppm)    TVOC[ppb]            points d'ancrage (calibration ci-dessous)
    GAS (MQ-135, ppm)    eCO2[ppm]            points d'ancrage
    SMOKE (ppm)          PM2.5                points d'ancrage
    HUMIDITY (%)         Humidity[%]          non utilisée par le modèle (voir README)
    MOTION               aucune               l'occupation reste gérée par les règles

Calibration par points d'ancrage : nos capteurs et ceux du dataset n'ont pas la même échelle
(le MQ-135 donne des "ppm" approximatifs, le dataset mesure le TVOC en ppb). Plutôt que d'inventer
une formule, on aligne des niveaux qui ont le même sens des deux côtés :

    haut de notre plage normale   ->  médiane des lignes SANS alarme du dataset
    seuil d'alerte de nos règles   ->  médiane des lignes AVEC alarme du dataset
    seuil HIGH de nos règles       ->  95e centile des lignes AVEC alarme

et on interpole linéairement entre ces points (valeurs bornées aux extrémités). Les quantiles
sont calculés sur la partie entraînement du dataset et enregistrés avec le modèle : infer.py
applique exactement la même conversion que celle validée à l'entraînement.
"""

import re

import numpy as np

# ---------- Colonnes du dataset ----------

# nom interne -> forme "normalisée" (minuscules, sans ponctuation) de la colonne Kaggle
DATASET_COLUMNS = {
    "utc": "utc",
    "temperature": "temperaturec",
    "humidity": "humidity",
    "tvoc_ppb": "tvocppb",
    "eco2_ppm": "eco2ppm",
    "pm25": "pm25",
    "fire_alarm": "firealarm",
}
REQUIRED_COLUMNS = ["utc", "temperature", "tvoc_ppb", "eco2_ppm", "pm25", "fire_alarm"]

# Variables d'entrée du modèle, dans cet ordre.
MODEL_FEATURES = ["temperature", "tvoc_ppb", "eco2_ppm", "pm25"]

# Contrainte de monotonie (+1) : à valeurs égales par ailleurs, plus de gaz, de fumée ou de chaleur
# ne doit jamais faire BAISSER le risque. Sans elle, le modèle apprend des artefacts du dataset
# (ex. : les lignes les plus chaudes y sont presque toutes sans alarme) et dirait "chaud = sûr".
MONOTONIC_CONSTRAINTS = [1, 1, 1, 1]

# ---------- Nos capteurs ----------

# Niveaux de référence de nos capteurs (simulateur et règles de détection de l'étape 2,
# DetectionEngine.GAS_THRESHOLD_PPM, GAS_HIGH_PPM, SMOKE_THRESHOLD_PPM).
SENSOR_REFERENCES = {
    "GAS": {"normal_max": 400.0, "alarm": 1000.0, "high": 1500.0},
    "SMOKE": {"normal_max": 50.0, "alarm": 300.0, "high": 600.0},
}

# Variable du modèle -> capteur qui la fournit
FEATURE_SOURCES = {
    "temperature": "TEMPERATURE",
    "tvoc_ppb": "GAS",
    "eco2_ppm": "GAS",
    "pm25": "SMOKE",
}

# Valeur neutre utilisée si la température d'une salle manque (température ambiante normale).
NEUTRAL_TEMPERATURE_C = 22.0


def normalize_name(name):
    """'Temperature[C]' -> 'temperaturec', 'PM2.5' -> 'pm25', 'Fire Alarm' -> 'firealarm'."""
    return re.sub(r"[^a-z0-9]", "", str(name).lower())


def detect_columns(columns):
    """
    Associe chaque nom interne à la colonne réelle du CSV, quelle que soit sa ponctuation ou sa
    casse. Lève ValueError en listant les colonnes manquantes.
    """
    by_normalized = {}
    for column in columns:
        by_normalized.setdefault(normalize_name(column), column)
    mapping, missing = {}, []
    for internal, normalized in DATASET_COLUMNS.items():
        if normalized in by_normalized:
            mapping[internal] = by_normalized[normalized]
        elif internal in REQUIRED_COLUMNS:
            missing.append(internal)
    if missing:
        raise ValueError(
            "Colonnes introuvables dans le CSV : " + ", ".join(missing)
            + ". Colonnes présentes : " + ", ".join(map(str, columns))
            + ". Est-ce bien smoke_detection_iot.csv du Smoke Detection Dataset ?")
    return mapping


# ---------- Calibration ----------

def fit_calibration(train_frame):
    """
    Calcule les points d'ancrage à partir des lignes d'entraînement (colonnes internes +
    fire_alarm). Renvoie un dict sérialisable en JSON, enregistré avec le modèle.
    """
    normal = train_frame[train_frame["fire_alarm"] == 0]
    alarm = train_frame[train_frame["fire_alarm"] == 1]
    if normal.empty or alarm.empty:
        raise ValueError("La calibration demande des lignes avec ET sans alarme.")

    calibration = {"method": "anchors-v1", "features": {}}
    for feature in ("tvoc_ppb", "eco2_ppm", "pm25"):
        sensor = FEATURE_SOURCES[feature]
        ref = SENSOR_REFERENCES[sensor]
        targets = [
            float(train_frame[feature].min()),
            float(normal[feature].quantile(0.50)),
            float(alarm[feature].quantile(0.50)),
            float(alarm[feature].quantile(0.95)),
        ]
        # L'interpolation demande des valeurs croissantes : on impose une suite non décroissante.
        targets = list(np.maximum.accumulate(targets))
        calibration["features"][feature] = {
            "sensor": sensor,
            "sensor_points": [0.0, ref["normal_max"], ref["alarm"], ref["high"]],
            "dataset_points": targets,
        }
    calibration["features"]["temperature"] = {
        "sensor": "TEMPERATURE",
        "identity_range": [float(train_frame["temperature"].min()), float(train_frame["temperature"].max())],
    }
    return calibration


def convert(feature, sensor_value, calibration):
    """Convertit une valeur de notre capteur vers l'unité de la colonne du dataset."""
    spec = calibration["features"][feature]
    if "identity_range" in spec:
        low, high = spec["identity_range"]
        return float(min(max(sensor_value, low), high))
    return float(np.interp(max(sensor_value, 0.0), spec["sensor_points"], spec["dataset_points"]))


def to_model_row(sensor_values, calibration):
    """
    sensor_values : {"GAS": 412.0, "SMOKE": 20.0, "TEMPERATURE": 23.1, ...} (dernières valeurs d'une salle).
    Renvoie (ligne, manquants) : ligne = {variable du modèle: valeur convertie}, dans l'ordre de
    MODEL_FEATURES ; manquants = capteurs absents, remplacés par une valeur neutre.

    Valeur neutre : le haut de la plage normale (GAS, SMOKE) ou 22 °C (TEMPERATURE). Un capteur
    absent ne fait donc ni monter ni baisser artificiellement le risque.
    """
    row, missing = {}, []
    for feature in MODEL_FEATURES:
        sensor = FEATURE_SOURCES[feature]
        value = sensor_values.get(sensor)
        if value is None:
            if sensor not in missing:
                missing.append(sensor)
            value = (NEUTRAL_TEMPERATURE_C if sensor == "TEMPERATURE"
                     else SENSOR_REFERENCES[sensor]["normal_max"])
        row[feature] = convert(feature, float(value), calibration)
    return row, missing


# ---------- Profils de contrôle ----------

# Situations tirées du simulateur (étape 2). Le modèle doit donner un risque bas pour les salles
# normales et plus haut pour une fuite de gaz ou un incendie : sinon il ne sert à rien pour nous,
# quels que soient ses scores sur le dataset. Ce contrôle est refait à chaque entraînement.
SCENARIO_PROFILES = {
    "normal-bas": {"GAS": 200.0, "SMOKE": 0.0, "TEMPERATURE": 20.0},
    "normal": {"GAS": 300.0, "SMOKE": 25.0, "TEMPERATURE": 23.0},
    "normal-haut": {"GAS": 400.0, "SMOKE": 50.0, "TEMPERATURE": 26.0},
    "fuite-de-gaz-debut": {"GAS": 1000.0, "SMOKE": 25.0, "TEMPERATURE": 23.0},
    "fuite-de-gaz-pic": {"GAS": 2000.0, "SMOKE": 25.0, "TEMPERATURE": 23.0},
    "incendie-milieu": {"GAS": 500.0, "SMOKE": 410.0, "TEMPERATURE": 47.0},
    "incendie-pic": {"GAS": 700.0, "SMOKE": 800.0, "TEMPERATURE": 70.0},
}
NORMAL_PROFILES = ["normal-bas", "normal", "normal-haut"]
DANGER_PROFILES = ["fuite-de-gaz-debut", "fuite-de-gaz-pic", "incendie-milieu", "incendie-pic"]
MAX_NORMAL_RISK = 0.5        # une salle normale doit rester sous ce score
MIN_DANGER_MARGIN = 0.2      # un danger doit dépasser la salle normale la plus haute d'au moins 0,2
