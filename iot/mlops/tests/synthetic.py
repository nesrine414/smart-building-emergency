"""
Petit dataset synthétique avec EXACTEMENT les colonnes du Smoke Detection Dataset, pour tester
toute la chaîne sans télécharger le vrai fichier. Les valeurs imitent ses ordres de grandeur :
les alarmes ont un TVOC autour de 1100 ppb, l'air normal autour de 60 ppb.
"""
import numpy as np
import pandas as pd

KAGGLE_COLUMNS = ["Unnamed: 0", "UTC", "Temperature[C]", "Humidity[%]", "TVOC[ppb]", "eCO2[ppm]",
                  "Raw H2", "Raw Ethanol", "Pressure[hPa]", "PM1.0", "PM2.5", "NC0.5", "NC1.0",
                  "NC2.5", "CNT", "Fire Alarm"]


def make_dataset(seconds=12_000, run_seconds=420, duplicated_rows=500, seed=7):
    rng = np.random.RandomState(seed)
    t = np.arange(seconds)
    alarm = ((t // run_seconds) % 3 != 0).astype(int)          # ~2/3 d'alarmes, par longues séquences
    frame = pd.DataFrame({
        "Unnamed: 0": t,
        "UTC": 1654733331 + t,
        "Temperature[C]": 20 + rng.normal(0, 3, seconds),
        "Humidity[%]": 50 + rng.normal(0, 4, seconds),
        "TVOC[ppb]": np.where(alarm == 1, rng.normal(1100, 120, seconds), np.abs(rng.normal(60, 40, seconds))).round(),
        "eCO2[ppm]": np.where(alarm == 1, 400 + np.abs(rng.normal(60, 40, seconds)), 400 + np.abs(rng.normal(5, 5, seconds))).round(),
        "Raw H2": rng.normal(12900, 100, seconds).round(),
        "Raw Ethanol": rng.normal(19500, 200, seconds).round(),
        "Pressure[hPa]": rng.normal(938.7, 0.5, seconds),
        "PM1.0": np.abs(rng.normal(1.5, 0.4, seconds)),
        "PM2.5": np.where(alarm == 1, np.abs(rng.normal(2.0, 0.3, seconds)), np.abs(rng.normal(1.4, 0.4, seconds))),
        "NC0.5": np.abs(rng.normal(10, 3, seconds)),
        "NC1.0": np.abs(rng.normal(1.6, 0.5, seconds)),
        "NC2.5": np.abs(rng.normal(0.04, 0.01, seconds)),
        "CNT": t,
        "Fire Alarm": alarm,
    })
    # Défaut du vrai fichier : une session enregistrée deux fois, seules l'heure et la température changent.
    copy = frame.iloc[:duplicated_rows].copy()
    copy["UTC"] = copy["UTC"] + 200_000
    copy["Unnamed: 0"] = copy["Unnamed: 0"] + seconds
    copy["Temperature[C]"] = copy["Temperature[C]"] - 5
    return pd.concat([frame, copy], ignore_index=True)[KAGGLE_COLUMNS]
