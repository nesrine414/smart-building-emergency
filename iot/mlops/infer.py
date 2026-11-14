"""
Inférence en direct : publie un score de risque d'incendie par salle (étape 3, contrat 6).

Le script se connecte au broker avec le compte sbe-ml, écoute les mesures GAS, SMOKE et
TEMPERATURE de toutes les salles, garde les dernières valeurs de chaque salle, les convertit vers
les unités du dataset (features.py, conversion validée à l'entraînement) et publie :

    topic   : smartbuilding/{bâtiment}/{salle}/RISK
    message : {"sensorId": "ml-101", "type": "RISK", "value": 0.87, "unit": "score",
               "timestamp": 1762940000000}

Le backend l'enregistre comme une mesure ordinaire ; les règles de détection l'ignorent ;
l'agent IA le lit avec getRiskScore. La conversion d'unités est faite ici, pas dans le
simulateur, pour ne pas changer les seuils des règles de l'étape 2.

Le score est une aide à la décision : il ne déclenche aucun incident ni aucun actionneur.

Utilisation (depuis iot/mlops, après train.py) :
    python infer.py --tls                  # compte sbe-ml, mot de passe MQTT_ML_PASSWORD du .env
    python infer.py --tls --dry-run        # calcule et affiche sans publier
    python infer.py --model-version 1      # une version précise au lieu de la version "current"
"""

import argparse
import json
import os
import ssl
import statistics
import threading
import time
from collections import defaultdict, deque
from pathlib import Path

import joblib
import pandas as pd
import paho.mqtt.client as mqtt

import features as F

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_MODELS = HERE / "models"
DEFAULT_CAFILE = REPO_ROOT / "infra" / "mqtt" / "certs" / "ca.pem"
ML_ACCOUNT = "sbe-ml"

# Capteurs utilisés par le modèle (l'humidité et la présence n'en font pas partie).
INPUT_TYPES = ("GAS", "SMOKE", "TEMPERATURE")


def load_env_file(path):
    """Charge KEY=VALUE depuis un fichier .env sans écraser les variables déjà définies."""
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


# ---------- Modèle ----------

def load_model(models_dir, version=None):
    """Charge la version demandée, ou la version "current" du registre (promue par train.py)."""
    models_dir = Path(models_dir)
    registry_path = models_dir / "registry.json"
    if version is None:
        if not registry_path.exists():
            raise SystemExit(f"Aucun modèle dans {models_dir} : lance d'abord python train.py")
        version = json.loads(registry_path.read_text(encoding="utf-8")).get("current")
        if version is None:
            raise SystemExit("Aucune version promue dans registry.json (contrôle de cohérence échoué) : "
                             "corrige l'entraînement ou choisis --model-version.")
    path = models_dir / f"model-v{version}.joblib"
    if not path.exists():
        raise SystemExit(f"Fichier introuvable : {path}")
    bundle = joblib.load(path)
    try:
        import sklearn
        if bundle.get("sklearn_version") != sklearn.__version__:
            print(f"Attention : modèle entraîné avec scikit-learn {bundle.get('sklearn_version')}, "
                  f"installé {sklearn.__version__}. En cas de souci, relance train.py.")
    except ImportError:
        pass
    return bundle


class RiskModel:
    def __init__(self, bundle):
        self.model = bundle["model"]
        self.features = bundle["features"]
        self.calibration = bundle["calibration"]
        self.version = bundle["version"]

    def risk(self, sensor_values):
        """Renvoie (score entre 0 et 1, capteurs manquants remplacés par une valeur neutre)."""
        row, missing = F.to_model_row(sensor_values, self.calibration)
        frame = pd.DataFrame([row], columns=self.features)
        return float(self.model.predict_proba(frame)[0, 1]), missing


# ---------- État des salles ----------

class RoomState:
    """
    Dernières mesures d'une salle. Une mesure n'est utilisée que si elle a été reçue il y a moins
    de freshness secondes (un capteur muet ne doit pas figer le score). Pour lisser le bruit, on
    prend la médiane des smoothing dernières valeurs encore fraîches.
    """

    def __init__(self, freshness=30.0, smoothing=3):
        self.freshness = freshness
        self.values = defaultdict(lambda: deque(maxlen=smoothing))
        self.last_published = 0.0
        self.first_seen = None

    def add(self, sensor_type, value, now):
        if self.first_seen is None:
            self.first_seen = now
        self.values[sensor_type].append((now, float(value)))

    def current(self, now):
        result = {}
        for sensor_type, history in self.values.items():
            fresh = [v for t, v in history if now - t <= self.freshness]
            if fresh:
                result[sensor_type] = statistics.median(fresh)
        return result


def room_sensor_id(room_id):
    """room-101 -> ml-101 (même numérotation que gas-101, temp-101...)."""
    return "ml-" + (room_id[5:] if room_id.startswith("room-") else room_id)


def risk_message(room_id, risk, now_ms):
    """Message du contrat 6."""
    return {"sensorId": room_sensor_id(room_id), "type": "RISK", "value": round(risk, 3),
            "unit": "score", "timestamp": now_ms}


def parse_reading(topic, payload):
    """Renvoie (bâtiment, salle, type, valeur) ou None si le message n'est pas une mesure utile."""
    parts = topic.split("/")
    if len(parts) != 4 or parts[0] != "smartbuilding" or parts[3] not in INPUT_TYPES:
        return None
    try:
        data = json.loads(payload)
        value = float(data["value"])
    except (ValueError, KeyError, TypeError):
        return None
    return parts[1], parts[2], parts[3], value


# ---------- Service MQTT ----------

class InferenceService:
    def __init__(self, model, args, clock=time.time):
        self.model = model
        self.args = args
        self.clock = clock
        self.rooms = defaultdict(lambda: RoomState(args.freshness, args.smoothing))
        self.lock = threading.Lock()
        self.client = None
        self.stopping = False

    def handle(self, topic, payload):
        """Traite une mesure ; renvoie (topic RISK, message) à publier, ou None."""
        reading = parse_reading(topic, payload)
        if reading is None:
            return None
        building, room_id, sensor_type, value = reading
        now = self.clock()
        with self.lock:
            room = self.rooms[(building, room_id)]
            room.add(sensor_type, value, now)
            if now - room.last_published < self.args.period:
                return None
            values = room.current(now)
            if "GAS" not in values and "SMOKE" not in values:
                return None          # pas assez d'information : on ne publie pas un score inventé
            if len(values) < len(INPUT_TYPES) and now - room.first_seen < self.args.period:
                return None          # salle qui vient d'apparaître : on attend ses autres capteurs
            room.last_published = now
        risk, missing = self.model.risk(values)
        message = risk_message(room_id, risk, int(now * 1000))
        inputs = ", ".join(f"{k} {v:.1f}" for k, v in sorted(values.items()))
        note = f" (manquant : {', '.join(missing)})" if missing else ""
        print(f"{building}/{room_id}  RISK {message['value']:.2f}  [{inputs}]{note}")
        return f"smartbuilding/{building}/{room_id}/RISK", message

    # --- callbacks paho ---
    def on_connect(self, client, userdata, flags, reason_code, properties):
        userdata["connack"] = reason_code
        if not reason_code.is_failure:
            for sensor_type in INPUT_TYPES:
                client.subscribe(f"smartbuilding/+/+/{sensor_type}", qos=1)
        userdata["connected"].set()

    def on_message(self, client, userdata, msg):
        try:
            result = self.handle(msg.topic, msg.payload.decode("utf-8", "replace"))
        except Exception as error:   # ne jamais tuer la boucle réseau sur un message bizarre
            print(f"  !! Message ignoré sur {msg.topic} : {error}")
            return
        if result and not self.args.dry_run:
            topic, message = result
            # Publication non bloquante depuis le callback (jamais de wait_for_publish ici).
            client.publish(topic, json.dumps(message), qos=1, retain=False)

    def on_disconnect(self, client, userdata, flags, reason_code, properties):
        if not self.stopping:
            print(f"Déconnecté du broker ({reason_code}) : reconnexion automatique...")

    def run(self):
        args = self.args
        userdata = {"connected": threading.Event(), "connack": None}
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"sbe-ml-infer-{os.getpid()}",
                             userdata=userdata)
        client.on_connect = self.on_connect
        client.on_message = self.on_message
        client.on_disconnect = self.on_disconnect
        if args.username:
            client.username_pw_set(args.username, args.password)
        if args.tls:
            if not Path(args.cafile).exists():
                raise SystemExit(f"Certificat de la CA introuvable : {args.cafile} (infra/mqtt/gen-certs)")
            client.tls_set(ca_certs=str(args.cafile))
        try:
            client.connect(args.host, args.port, keepalive=60)
        except ssl.SSLCertVerificationError as error:
            raise SystemExit(f"Certificat du broker refusé ({error.verify_message}) : vérifie --cafile et --host.")
        except (ssl.SSLError, OSError) as error:
            raise SystemExit(f"Impossible de joindre {args.host}:{args.port} ({error}).")
        client.loop_start()
        if not userdata["connected"].wait(15):
            client.loop_stop()
            raise SystemExit(f"Pas de réponse du broker {args.host}:{args.port} (port TLS sans --tls ?)")
        if userdata["connack"].is_failure:
            client.loop_stop()
            raise SystemExit(f"Connexion refusée par le broker ({userdata['connack']}) : vérifie MQTT_ML_PASSWORD "
                             "dans .env et credentials.xml (infra/mqtt/gen_credentials.py).")
        self.client = client
        mode = "TLS" if args.tls else "sans chiffrement"
        print(f"Connecté à {args.host}:{args.port} ({mode}, compte {args.username or 'anonyme'}), "
              f"modèle v{self.model.version}. Score publié au plus toutes les {args.period:g} s par salle"
              f"{' (essai à blanc : aucune publication)' if args.dry_run else ''}. Ctrl+C pour arrêter.")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\nArrêt demandé.")
        finally:
            self.stopping = True
            client.disconnect()
            client.loop_stop()


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Publie le score de risque ML de chaque salle (contrat 6)")
    parser.add_argument("--host", default="localhost", help="adresse du broker (défaut : localhost)")
    parser.add_argument("--port", type=int, default=None, help="port (défaut : 8883 avec --tls, 1883 sinon)")
    parser.add_argument("--username", default=ML_ACCOUNT, help=f"compte MQTT (défaut : {ML_ACCOUNT})")
    parser.add_argument("--password", default=None, help="mot de passe (défaut : MQTT_ML_PASSWORD, variable ou .env)")
    parser.add_argument("--tls", action="store_true", help="connexion chiffrée MQTTS (port 8883)")
    parser.add_argument("--cafile", default=str(DEFAULT_CAFILE), help="certificat de la CA du projet")
    parser.add_argument("--models-dir", default=str(DEFAULT_MODELS), help="dossier des modèles (défaut : models)")
    parser.add_argument("--model-version", type=int, default=None, help="version à utiliser (défaut : current)")
    parser.add_argument("--period", type=float, default=5.0, help="secondes minimum entre deux scores d'une salle")
    parser.add_argument("--freshness", type=float, default=30.0, help="âge maximum d'une mesure utilisée (s)")
    parser.add_argument("--smoothing", type=int, default=3, help="médiane des N dernières mesures (défaut : 3)")
    parser.add_argument("--dry-run", action="store_true", help="calcule et affiche sans publier")
    args = parser.parse_args(argv)
    if args.port is None:
        args.port = 8883 if args.tls else 1883
    if args.password is None:
        args.password = os.environ.get("MQTT_ML_PASSWORD") or None
    if not args.password:
        args.username = None
    return args


def main():
    load_env_file(REPO_ROOT / ".env")
    args = parse_args()
    model = RiskModel(load_model(args.models_dir, args.model_version))
    InferenceService(model, args).run()


if __name__ == "__main__":
    main()
