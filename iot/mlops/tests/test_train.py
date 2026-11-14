import json

import numpy as np
import pytest

import train
from synthetic import make_dataset


@pytest.fixture(scope="module")
def csv_path(tmp_path_factory):
    path = tmp_path_factory.mktemp("data") / "smoke_detection_iot.csv"
    make_dataset().to_csv(path, index=False)
    return path


def run(csv_path, models_dir, notes=""):
    args = train.parse_args(["--data", str(csv_path), "--models-dir", str(models_dir), "--notes", notes])
    return train.train(args)


def test_doublons_de_session_retires(csv_path):
    data, info = train.load_dataset(csv_path)
    assert info["rows_duplicated_removed"] == 500
    assert info["rows_used"] == info["rows_raw"] - 500
    assert data["utc"].is_monotonic_increasing


def test_decoupage_par_blocs_sans_chevauchement(csv_path):
    data, _ = train.load_dataset(csv_path)
    train_mask, test_mask = train.block_split(data, block_minutes=10, test_every=5, purge_seconds=60)
    assert not (train_mask & test_mask).any()
    assert 0.15 < test_mask.mean() < 0.25
    # Aucune ligne d'entraînement à 60 s ou moins d'une ligne de test (marge de purge)
    t_train = data.loc[train_mask, "utc"].to_numpy()
    t_test = np.sort(data.loc[test_mask, "utc"].to_numpy())
    index = np.clip(np.searchsorted(t_test, t_train), 1, len(t_test) - 1)
    distance = np.minimum(np.abs(t_train - t_test[index - 1]), np.abs(t_train - t_test[index]))
    assert distance.min() > 60


def test_entrainement_versionne_sans_ecraser(csv_path, tmp_path):
    models = tmp_path / "models"
    assert run(csv_path, models, "premier") == 0
    assert run(csv_path, models, "second") == 0
    registry = json.loads((models / "registry.json").read_text(encoding="utf-8"))
    assert [v["version"] for v in registry["versions"]] == [1, 2]
    assert registry["current"] == 2
    for version in (1, 2):
        assert (models / f"model-v{version}.joblib").exists()
        assert (models / f"metrics-v{version}.json").exists()


def test_metriques_completes_et_reproductibles(csv_path, tmp_path):
    models = tmp_path / "models"
    run(csv_path, models)
    run(csv_path, models)
    m1 = json.loads((models / "metrics-v1.json").read_text(encoding="utf-8"))
    m2 = json.loads((models / "metrics-v2.json").read_text(encoding="utf-8"))
    for key in ("precision", "recall", "f1", "roc_auc", "confusion_matrix"):
        assert key in m1["test_metrics"]
    assert m1["features"] == ["temperature", "tvoc_ppb", "eco2_ppm", "pm25"]
    assert m1["dataset"]["sha256"] and m1["trained_at"] and m1["version"] == 1
    assert m1["split"]["method"] == "blocs de temps"
    assert m1["test_metrics"] == m2["test_metrics"]          # random_state fixé
    assert m1["scenario_check"]["passed"] is True
    risks = m1["scenario_check"]["risk_by_profile"]
    assert risks["incendie-pic"] > risks["normal"] and risks["fuite-de-gaz-pic"] > risks["normal"]


def test_dataset_absent_message_clair(tmp_path):
    with pytest.raises(SystemExit, match="kaggle"):
        run(tmp_path / "absent.csv", tmp_path / "models")
