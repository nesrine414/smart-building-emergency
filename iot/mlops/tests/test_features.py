import pandas as pd
import pytest

import features as F


def test_detecte_les_colonnes_kaggle():
    columns = ["Unnamed: 0", "UTC", "Temperature[C]", "Humidity[%]", "TVOC[ppb]", "eCO2[ppm]", "PM2.5", "Fire Alarm"]
    mapping = F.detect_columns(columns)
    assert mapping["tvoc_ppb"] == "TVOC[ppb]"
    assert mapping["pm25"] == "PM2.5"
    assert mapping["fire_alarm"] == "Fire Alarm"


def test_detection_insensible_a_la_casse_et_a_la_ponctuation():
    mapping = F.detect_columns(["utc", "temperature_c", "TVOC ppb", "eco2-ppm", "pm2_5", "fire_alarm"])
    assert mapping["temperature"] == "temperature_c"
    assert mapping["pm25"] == "pm2_5"


def test_colonne_manquante_message_clair():
    with pytest.raises(ValueError, match="pm25"):
        F.detect_columns(["UTC", "Temperature[C]", "TVOC[ppb]", "eCO2[ppm]", "Fire Alarm"])


@pytest.fixture
def calibration():
    frame = pd.DataFrame({
        "temperature": [10, 20, 30, 40],
        "tvoc_ppb": [0, 80, 1100, 1300],
        "eco2_ppm": [400, 400, 420, 800],
        "pm25": [0.5, 1.5, 2.0, 2.6],
        "fire_alarm": [0, 0, 1, 1],
    })
    return F.fit_calibration(frame)


def test_ancrages_de_la_calibration(calibration):
    gas = F.SENSOR_REFERENCES["GAS"]
    tvoc = calibration["features"]["tvoc_ppb"]["dataset_points"]
    # haut de la plage normale -> médiane sans alarme ; seuil d'alerte -> médiane des alarmes
    assert F.convert("tvoc_ppb", gas["normal_max"], calibration) == pytest.approx(tvoc[1])
    assert F.convert("tvoc_ppb", gas["alarm"], calibration) == pytest.approx(tvoc[2])
    # au-delà du seuil HIGH, la valeur reste bornée au 95e centile des alarmes
    assert F.convert("tvoc_ppb", 99_999, calibration) == pytest.approx(tvoc[3])


def test_conversion_croissante(calibration):
    values = [F.convert("pm25", v, calibration) for v in range(0, 1000, 25)]
    assert values == sorted(values)


def test_temperature_identite_bornee(calibration):
    assert F.convert("temperature", 25.0, calibration) == 25.0
    assert F.convert("temperature", 80.0, calibration) == 40.0


def test_capteur_manquant_remplace_par_valeur_neutre(calibration):
    row, missing = F.to_model_row({"GAS": 300.0}, calibration)
    assert set(missing) == {"SMOKE", "TEMPERATURE"}
    assert list(row) == F.MODEL_FEATURES
    assert row["temperature"] == F.NEUTRAL_TEMPERATURE_C
    assert row["pm25"] == pytest.approx(F.convert("pm25", F.SENSOR_REFERENCES["SMOKE"]["normal_max"], calibration))


def test_profils_de_controle_coherents():
    for name in F.NORMAL_PROFILES + F.DANGER_PROFILES:
        assert set(F.SCENARIO_PROFILES[name]) == {"GAS", "SMOKE", "TEMPERATURE"}
