"""
Entraîne le modèle de risque d'incendie sur le Smoke Detection Dataset (étape 3, personne 1).

Étapes :
  1. Lecture du CSV, détection robuste des colonnes, suppression des doublons.
     Le fichier Kaggle contient des sessions enregistrées deux fois (mêmes valeurs, seule la
     température et l'heure changent) : sans ce nettoyage, le test contient des copies de
     l'entraînement et les scores sont faux.
  2. Découpage entraînement / test PAR BLOCS DE TEMPS (pas au hasard) : les lignes voisines,
     prises à une seconde d'écart, se ressemblent presque à l'identique. Un découpage au hasard
     met des quasi-copies des deux côtés et donne des scores trop optimistes. Une marge
     ("purge") est retirée de l'entraînement autour de chaque bloc de test.
  3. RandomForestClassifier sur l'étiquette Fire Alarm, avec contrainte de monotonie
     (plus de gaz / fumée / chaleur ne fait jamais baisser le risque), random_state fixé.
  4. Comparaisons enregistrées dans les métriques :
       - l'approche naïve (CSV brut avec doublons, découpage au hasard, sans contrainte) ;
       - le même modèle évalué avec un découpage au hasard ;
       - une forêt sans contrainte de monotonie ;
       - un IsolationForest (détection d'anomalies sans étiquette), entraîné sur les lignes normales.
  5. Contrôle de cohérence sur nos scénarios (normal, fuite de gaz, incendie) après conversion
     des unités : un modèle qui ne distingue pas ces situations n'est pas promu.
  6. Sauvegarde versionnée, sans jamais écraser : models/model-vN.joblib, models/metrics-vN.json,
     et une entrée dans models/registry.json. infer.py utilise la version "current" du registre.

Utilisation (depuis iot/mlops) :
    python train.py                                  # data/smoke_detection_iot.csv
    python train.py --data chemin/vers/fichier.csv --notes "essai blocs de 20 min"
"""

import argparse
import hashlib
import json
import platform
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.ensemble import IsolationForest, RandomForestClassifier
from sklearn.metrics import (confusion_matrix, f1_score, precision_score, recall_score,
                             roc_auc_score)

import features as F

HERE = Path(__file__).resolve().parent
DEFAULT_DATA = HERE / "data" / "smoke_detection_iot.csv"
DEFAULT_MODELS = HERE / "models"
DATASET_URL = "https://www.kaggle.com/datasets/deepcontractor/smoke-detection-dataset"

RANDOM_STATE = 42
RF_PARAMS = {
    "n_estimators": 200,
    "max_depth": 12,
    "min_samples_leaf": 20,
    "class_weight": "balanced",   # le dataset a 71 % d'alarmes : on ne veut pas de ce biais chez nous
    "random_state": RANDOM_STATE,
    "n_jobs": -1,
}
IFOREST_PARAMS = {"n_estimators": 200, "random_state": RANDOM_STATE, "n_jobs": -1}


# ---------- Données ----------

def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_dataset(path, deduplicate=True):
    """Renvoie (données aux noms internes triées par temps, infos de nettoyage)."""
    raw = pd.read_csv(path)
    mapping = F.detect_columns(raw.columns)

    # Doublons : toutes les colonnes de mesure identiques sauf la température et l'heure.
    ignored = {mapping["utc"], mapping["temperature"]}
    ignored |= {c for c in raw.columns if F.normalize_name(c).startswith("unnamed") or c == ""}
    duplicate_key = [c for c in raw.columns if c not in ignored]
    raw = raw.sort_values(mapping["utc"], kind="stable")
    duplicated = raw.duplicated(subset=duplicate_key, keep="first")
    if not deduplicate:
        duplicated[:] = False
    clean = raw.loc[~duplicated]

    data = pd.DataFrame({internal: clean[column] for internal, column in mapping.items()})
    data = data.dropna(subset=F.REQUIRED_COLUMNS)
    data["fire_alarm"] = data["fire_alarm"].astype(int)
    data = data.reset_index(drop=True)
    info = {
        "rows_raw": int(len(raw)),
        "rows_duplicated_removed": int(duplicated.sum()),
        "rows_used": int(len(data)),
        "alarm_rate": round(float(data["fire_alarm"].mean()), 4),
        "columns_detected": mapping,
        "duplicate_rule": "mêmes valeurs sur toutes les colonnes sauf UTC, température et index",
    }
    return data, info


def block_split(data, block_minutes, test_every, purge_seconds):
    """
    Découpe le temps en blocs de block_minutes ; un bloc sur test_every va au test.
    Les lignes d'entraînement à moins de purge_seconds d'un bloc de test sont retirées.
    Renvoie (masque entraînement, masque test).
    """
    t = data["utc"].to_numpy(dtype=np.int64)
    block_seconds = block_minutes * 60
    block = (t - t.min()) // block_seconds
    offset = test_every // 2                    # bloc de test au milieu de chaque groupe
    test = (block % test_every) == offset

    # Distance de chaque ligne d'entraînement au bloc de test le plus proche
    position = (t - t.min()) % block_seconds
    before_test = (block % test_every) == (offset - 1) % test_every
    after_test = (block % test_every) == (offset + 1) % test_every
    near = (before_test & (position >= block_seconds - purge_seconds)) | (after_test & (position < purge_seconds))
    train = ~test & ~near
    return train, test


def check_both_classes(labels, name):
    counts = labels.value_counts().to_dict()
    if counts.get(0, 0) == 0 or counts.get(1, 0) == 0:
        raise SystemExit(f"La partie {name} ne contient qu'une seule classe ({counts}) : "
                         "change --block-minutes ou --test-every.")
    return {"rows": int(len(labels)), "alarms": int(counts.get(1, 0)), "no_alarm": int(counts.get(0, 0))}


# ---------- Évaluation ----------

def classification_metrics(y_true, probability, threshold=0.5):
    predicted = (probability >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_true, predicted, labels=[0, 1]).ravel()
    return {
        "threshold": threshold,
        "precision": round(float(precision_score(y_true, predicted, zero_division=0)), 4),
        "recall": round(float(recall_score(y_true, predicted, zero_division=0)), 4),
        "f1": round(float(f1_score(y_true, predicted, zero_division=0)), 4),
        "roc_auc": round(float(roc_auc_score(y_true, probability)), 4),
        "confusion_matrix": {"true_negative": int(tn), "false_positive": int(fp),
                             "false_negative": int(fn), "true_positive": int(tp)},
    }


def scenario_checks(model, calibration):
    """Risque prédit pour nos scénarios du simulateur, et verdict du contrôle de cohérence."""
    risks = {}
    for name, sensors in F.SCENARIO_PROFILES.items():
        row, _ = F.to_model_row(sensors, calibration)
        frame = pd.DataFrame([row], columns=F.MODEL_FEATURES)
        risks[name] = round(float(model.predict_proba(frame)[0, 1]), 3)
    worst_normal = max(risks[n] for n in F.NORMAL_PROFILES)
    problems = []
    if worst_normal >= F.MAX_NORMAL_RISK:
        problems.append(f"salle normale à {worst_normal} (max {F.MAX_NORMAL_RISK})")
    for name in F.DANGER_PROFILES:
        if risks[name] < worst_normal + F.MIN_DANGER_MARGIN:
            problems.append(f"{name} à {risks[name]}, pas assez au-dessus du normal ({worst_normal})")
    return {"risk_by_profile": risks, "profiles": F.SCENARIO_PROFILES,
            "passed": not problems, "problems": problems}


# ---------- Registre des versions ----------

def load_registry(models_dir):
    path = models_dir / "registry.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"current": None, "versions": []}


def next_version(models_dir, registry):
    used = [entry["version"] for entry in registry["versions"]]
    used += [int(p.stem.split("-v")[1]) for p in models_dir.glob("model-v*.joblib") if p.stem.split("-v")[1].isdigit()]
    return max(used, default=0) + 1


def git_commit():
    try:
        out = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=HERE, capture_output=True,
                             text=True, timeout=5)
        return out.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


# ---------- Programme principal ----------

def train(args):
    started = time.time()
    data_path = Path(args.data)
    if not data_path.exists():
        raise SystemExit(f"Dataset introuvable : {data_path}\n"
                         f"Télécharge smoke_detection_iot.csv depuis {DATASET_URL} dans {data_path.parent}.")

    print(f"Lecture de {data_path}")
    data, data_info = load_dataset(data_path)
    print(f"  {data_info['rows_raw']} lignes, {data_info['rows_duplicated_removed']} doublons retirés, "
          f"{data_info['rows_used']} utilisées ({data_info['alarm_rate']:.0%} d'alarmes)")

    train_mask, test_mask = block_split(data, args.block_minutes, args.test_every, args.purge_seconds)
    y = data["fire_alarm"]
    split_info = {
        "method": "blocs de temps",
        "block_minutes": args.block_minutes,
        "test_every": args.test_every,
        "purge_seconds": args.purge_seconds,
        "train": check_both_classes(y[train_mask], "entraînement"),
        "test": check_both_classes(y[test_mask], "test"),
        "purged_rows": int((~train_mask & ~test_mask).sum()),
    }
    print(f"  entraînement {split_info['train']['rows']} lignes, test {split_info['test']['rows']} lignes "
          f"(blocs de {args.block_minutes} min, 1 sur {args.test_every} en test)")

    X = data[F.MODEL_FEATURES]
    X_train, y_train = X[train_mask], y[train_mask]
    X_test, y_test = X[test_mask], y[test_mask]

    print("Entraînement du RandomForest (monotone)")
    model = RandomForestClassifier(monotonic_cst=F.MONOTONIC_CONSTRAINTS, **RF_PARAMS)
    model.fit(X_train, y_train)
    main_metrics = classification_metrics(y_test, model.predict_proba(X_test)[:, 1])

    print("Comparaisons : découpage au hasard, forêt sans contrainte, IsolationForest")
    rng = np.random.RandomState(RANDOM_STATE)
    random_test = rng.rand(len(data)) < (1 / args.test_every)
    random_model = RandomForestClassifier(monotonic_cst=F.MONOTONIC_CONSTRAINTS, **RF_PARAMS)
    random_model.fit(X[~random_test], y[~random_test])
    random_metrics = classification_metrics(y[random_test], random_model.predict_proba(X[random_test])[:, 1])

    free_model = RandomForestClassifier(**RF_PARAMS).fit(X_train, y_train)
    free_metrics = classification_metrics(y_test, free_model.predict_proba(X_test)[:, 1])

    # IsolationForest : appris sur les lignes normales seulement (aucune étiquette d'incendie).
    # Le seuil de décision est choisi sur l'entraînement (meilleur F1), puis appliqué au test.
    iforest = IsolationForest(**IFOREST_PARAMS).fit(X_train[y_train == 0])
    anomaly_train = -iforest.score_samples(X_train)
    anomaly_test = -iforest.score_samples(X_test)
    candidates = np.quantile(anomaly_train, np.linspace(0.01, 0.99, 99))
    threshold = float(max(candidates, key=lambda c: f1_score(y_train, (anomaly_train >= c).astype(int),
                                                            zero_division=0)))
    iforest_metrics = classification_metrics(y_test, anomaly_test, threshold=threshold)
    iforest_metrics["threshold"] = round(threshold, 4)
    iforest_metrics["note"] = ("score d'anomalie appris sans étiquette sur les lignes normales ; "
                               "seuil choisi sur l'entraînement (meilleur F1). Score sans borne fixe "
                               "et à double sens (trop bas compte aussi comme anormal) : pas utilisé comme RISK")

    # Référence triviale : toujours prédire "alarme". Avec ~70 % d'alarmes dans le test, son F1 est
    # déjà élevé : c'est le chiffre à battre, et la raison de regarder aussi l'AUC.
    baseline_metrics = classification_metrics(y_test, np.ones(len(y_test)))
    baseline_metrics.pop("roc_auc")

    # Approche naïve, pour mémoire : CSV brut (avec ses doublons), découpage au hasard, sans contrainte.
    raw_data, _ = load_dataset(data_path, deduplicate=False)
    raw_test = np.random.RandomState(RANDOM_STATE).rand(len(raw_data)) < (1 / args.test_every)
    naive_model = RandomForestClassifier(**RF_PARAMS).fit(raw_data.loc[~raw_test, F.MODEL_FEATURES],
                                                         raw_data.loc[~raw_test, "fire_alarm"])
    naive_metrics = classification_metrics(raw_data.loc[raw_test, "fire_alarm"],
                                           naive_model.predict_proba(raw_data.loc[raw_test, F.MODEL_FEATURES])[:, 1])

    calibration = F.fit_calibration(data[train_mask])
    sanity = scenario_checks(model, calibration)
    free_sanity = scenario_checks(free_model, calibration)
    naive_sanity = scenario_checks(naive_model, calibration)
    iforest_metrics["anomaly_by_profile"] = {
        name: round(float(-iforest.score_samples(pd.DataFrame([F.to_model_row(p, calibration)[0]],
                                                              columns=F.MODEL_FEATURES))[0]), 3)
        for name, p in F.SCENARIO_PROFILES.items()}

    models_dir = Path(args.models_dir)
    models_dir.mkdir(parents=True, exist_ok=True)
    registry = load_registry(models_dir)
    version = next_version(models_dir, registry)
    trained_at = datetime.now(timezone.utc).isoformat(timespec="seconds")

    metrics = {
        "version": version,
        "trained_at": trained_at,
        "notes": args.notes,
        "dataset": {"name": "Smoke Detection Dataset", "url": DATASET_URL, "file": data_path.name,
                    "sha256": sha256_of(data_path), **data_info},
        "features": F.MODEL_FEATURES,
        "monotonic_constraints": F.MONOTONIC_CONSTRAINTS,
        "label": "Fire Alarm",
        "model": {"type": "RandomForestClassifier", "params": {k: v for k, v in RF_PARAMS.items() if k != "n_jobs"}},
        "split": split_info,
        "test_metrics": main_metrics,
        "comparisons": {
            "approche_naive_csv_brut_hasard_sans_contrainte": {
                **naive_metrics, "scenario_check_passed": naive_sanity["passed"],
                "risk_by_profile": naive_sanity["risk_by_profile"],
                "note": "doublons gardés + découpage au hasard : le test contient des copies de l'entraînement"},
            "reference_toujours_alarme": baseline_metrics,
            "meme_modele_decoupage_au_hasard": random_metrics,
            "foret_sans_contrainte_de_monotonie": {**free_metrics, "scenario_check_passed": free_sanity["passed"],
                                                   "risk_by_profile": free_sanity["risk_by_profile"]},
            "isolation_forest": iforest_metrics,
        },
        "calibration": calibration,
        "scenario_check": sanity,
        "environment": {"python": platform.python_version(), "scikit_learn": sklearn.__version__,
                        "pandas": pd.__version__, "numpy": np.__version__, "git_commit": git_commit()},
        "training_seconds": round(time.time() - started, 1),
    }

    bundle = {
        "version": version,
        "model": model,
        "features": F.MODEL_FEATURES,
        "calibration": calibration,
        "trained_at": trained_at,
        "sklearn_version": sklearn.__version__,
    }
    model_path = models_dir / f"model-v{version}.joblib"
    metrics_path = models_dir / f"metrics-v{version}.json"
    joblib.dump(bundle, model_path, compress=3)
    metrics_path.write_text(json.dumps(metrics, indent=2, ensure_ascii=False), encoding="utf-8")

    entry = {
        "version": version,
        "trained_at": trained_at,
        "model_file": model_path.name,
        "metrics_file": metrics_path.name,
        "f1": main_metrics["f1"],
        "recall": main_metrics["recall"],
        "precision": main_metrics["precision"],
        "scenario_check_passed": sanity["passed"],
        "dataset_sha256": metrics["dataset"]["sha256"],
        "notes": args.notes,
    }
    registry["versions"].append(entry)
    if sanity["passed"]:
        registry["current"] = version   # promotion : infer.py utilisera cette version
    (models_dir / "registry.json").write_text(json.dumps(registry, indent=2, ensure_ascii=False), encoding="utf-8")

    print_summary(metrics, model_path, metrics_path, registry)
    return 0 if sanity["passed"] else 2


def print_summary(metrics, model_path, metrics_path, registry):
    m = metrics["test_metrics"]
    c = metrics["comparisons"]
    print()
    print(f"Version {metrics['version']} enregistrée : {model_path.name}, {metrics_path.name}")
    print(f"  Test par blocs de temps : précision {m['precision']:.3f}, rappel {m['recall']:.3f}, "
          f"F1 {m['f1']:.3f}, AUC {m['roc_auc']:.3f}")
    cm = m["confusion_matrix"]
    print(f"  Matrice de confusion : VN {cm['true_negative']}  FP {cm['false_positive']}  "
          f"FN {cm['false_negative']}  VP {cm['true_positive']}")
    naive = c["approche_naive_csv_brut_hasard_sans_contrainte"]
    free = c["foret_sans_contrainte_de_monotonie"]
    print("  Pour comparaison :")
    print(f"    référence « toujours alarme »                       F1 {c['reference_toujours_alarme']['f1']:.3f}")
    print(f"    approche naïve (doublons + hasard + sans contrainte) F1 {naive['f1']:.3f}, "
          f"contrôle des scénarios {'réussi' if naive['scenario_check_passed'] else 'ÉCHOUÉ'}")
    print(f"    même modèle, découpage au hasard                    F1 {c['meme_modele_decoupage_au_hasard']['f1']:.3f}")
    print(f"    forêt sans contrainte de monotonie                  F1 {free['f1']:.3f}, "
          f"contrôle des scénarios {'réussi' if free['scenario_check_passed'] else 'ÉCHOUÉ'}")
    print(f"    IsolationForest (sans étiquette)                    F1 {c['isolation_forest']['f1']:.3f}, "
          f"AUC {c['isolation_forest']['roc_auc']:.3f}")
    print("  Risque prédit sur nos scénarios :")
    for name, risk in metrics["scenario_check"]["risk_by_profile"].items():
        print(f"    {name:<20} {risk:.2f}")
    if metrics["scenario_check"]["passed"]:
        print(f"Contrôle de cohérence réussi : version {registry['current']} promue (registry.json).")
    else:
        print("Contrôle de cohérence ÉCHOUÉ : version enregistrée mais PAS promue.")
        for problem in metrics["scenario_check"]["problems"]:
            print(f"  - {problem}")
        print(f"  infer.py continue d'utiliser la version {registry['current']}.")


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Entraîne le modèle de risque (Smoke Detection Dataset)")
    parser.add_argument("--data", default=str(DEFAULT_DATA), help="CSV du dataset (défaut : data/smoke_detection_iot.csv)")
    parser.add_argument("--models-dir", default=str(DEFAULT_MODELS), help="dossier des versions (défaut : models)")
    parser.add_argument("--block-minutes", type=int, default=10, help="taille des blocs de temps (défaut : 10)")
    parser.add_argument("--test-every", type=int, default=5, help="un bloc sur N va au test (défaut : 5, soit 20 %%)")
    parser.add_argument("--purge-seconds", type=int, default=60,
                        help="marge retirée de l'entraînement autour des blocs de test (défaut : 60)")
    parser.add_argument("--notes", default="", help="commentaire libre enregistré avec la version")
    args = parser.parse_args(argv)
    if args.test_every < 3:
        parser.error("--test-every doit valoir au moins 3")
    if args.purge_seconds * 2 >= args.block_minutes * 60:
        parser.error("--purge-seconds trop grand pour la taille des blocs")
    return args


if __name__ == "__main__":
    sys.exit(train(parse_args()))
