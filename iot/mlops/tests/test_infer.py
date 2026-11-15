import json
from types import SimpleNamespace

import pytest

import infer
import train
from synthetic import make_dataset


class FakeClock:
    def __init__(self, start=1_000.0):
        self.now = start

    def __call__(self):
        return self.now


@pytest.fixture(scope="module")
def model(tmp_path_factory):
    folder = tmp_path_factory.mktemp("ml")
    csv = folder / "data.csv"
    make_dataset().to_csv(csv, index=False)
    train.train(train.parse_args(["--data", str(csv), "--models-dir", str(folder / "models")]))
    return infer.RiskModel(infer.load_model(folder / "models"))


def service(model, clock, period=5.0):
    args = SimpleNamespace(freshness=30.0, smoothing=3, period=period, dry_run=True)
    return infer.InferenceService(model, args, clock=clock)


def reading(value):
    return json.dumps({"sensorId": "x", "value": value, "unit": "ppm", "timestamp": 0})


def test_message_conforme_au_contrat_6():
    message = infer.risk_message("room-101", 0.87349, 1762940000000)
    assert message == {"sensorId": "ml-101", "type": "RISK", "value": 0.873, "unit": "score",
                       "timestamp": 1762940000000}


def test_seules_les_mesures_utiles_sont_lues():
    assert infer.parse_reading("smartbuilding/bld-01/room-101/GAS", reading(400)) == ("bld-01", "room-101", "GAS", 400.0)
    for topic in ("smartbuilding/bld-01/room-101/cmd", "smartbuilding/bld-01/room-101/RISK",
                  "smartbuilding/bld-01/room-101/HUMIDITY", "autre/bld-01/room-101/GAS"):
        assert infer.parse_reading(topic, reading(1)) is None
    assert infer.parse_reading("smartbuilding/bld-01/room-101/GAS", "pas du json") is None


def test_mesure_trop_ancienne_ignoree():
    room = infer.RoomState(freshness=30, smoothing=3)
    room.add("GAS", 1500, now=0)
    room.add("SMOKE", 20, now=25)
    assert room.current(now=40) == {"SMOKE": 20}


def test_mediane_des_dernieres_valeurs():
    room = infer.RoomState(freshness=30, smoothing=3)
    for value in (300, 5000, 310, 320):          # un pic isolé ne fait pas sauter le score
        room.add("GAS", value, now=1)
    assert room.current(now=2)["GAS"] == 320


def test_publication_limitee_par_salle(model):
    clock = FakeClock()
    svc = service(model, clock)
    topic = "smartbuilding/bld-01/room-101/"
    for sensor, value in (("GAS", 300), ("SMOKE", 20), ("TEMPERATURE", 22)):
        result = svc.handle(topic + sensor, reading(value))
    assert result is not None and result[0] == "smartbuilding/bld-01/room-101/RISK"
    clock.now += 1
    assert svc.handle(topic + "GAS", reading(300)) is None        # moins de 5 s après le précédent
    clock.now += 5
    assert svc.handle(topic + "GAS", reading(300)) is not None


def test_attend_les_autres_capteurs_avant_le_premier_score(model):
    clock = FakeClock()
    svc = service(model, clock)
    assert svc.handle("smartbuilding/b/room-1/GAS", reading(300)) is None
    clock.now += 6                                                # capteurs toujours absents : score quand même
    result = svc.handle("smartbuilding/b/room-1/GAS", reading(300))
    assert result is not None


def test_pas_de_score_sans_gaz_ni_fumee(model):
    clock = FakeClock()
    svc = service(model, clock)
    clock.now += 10
    assert svc.handle("smartbuilding/b/room-1/TEMPERATURE", reading(22)) is None


def test_le_risque_monte_avec_le_danger(model):
    normal, _ = model.risk({"GAS": 300, "SMOKE": 25, "TEMPERATURE": 23})
    leak, _ = model.risk({"GAS": 2000, "SMOKE": 25, "TEMPERATURE": 23})
    assert 0.0 <= normal < leak <= 1.0
