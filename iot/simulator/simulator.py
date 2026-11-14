"""
Simulateur de capteurs pour le projet Smart Building Emergency.

Publie des mesures simulées (gaz, fumée, température, humidité, mouvement)
sur le broker MQTT, au format convenu dans le plan de la démo (contrat 3) :

    topic   : smartbuilding/{buildingId}/{roomId}/{type}
    message : {"sensorId": "...", "type": "GAS", "value": 412.5,
               "unit": "ppm", "timestamp": 1762354800000}

Il joue aussi le rôle des actionneurs (étape 2, contrat 9) : il reçoit les commandes du
backend sur smartbuilding/{buildingId}/{roomId}/cmd, les affiche et les confirme sur
smartbuilding/{buildingId}/{roomId}/actuator. Quand la ventilation tourne pendant une
fuite de gaz, le gaz redescend.

Sécurité (étape 3, contrat 5) : le simulateur se connecte avec le compte sbe-device. Son mot de
passe est lu dans la variable MQTT_DEVICE_PASSWORD, ou dans le fichier .env à la racine du dépôt.
Avec --tls, la connexion est chiffrée (port 8883) et le certificat du broker est vérifié avec la
CA du projet (infra/mqtt/certs/ca.pem).

Exemples :
    python simulator.py                         # scénario normal, toutes les 2 s
    python simulator.py --scenario gas-leak     # fuite de gaz dans la 1re salle
    python simulator.py --scenario fire --count 20
    python simulator.py --host 192.168.1.20 --interval 1
    python simulator.py --tls --scenario gas-leak          # étape 3 : TLS + identifiants du .env
    python simulator.py --tls --rooms 201,101,102 --scenario fire   # incendie en salle 201
"""

import argparse
import json
import os
import random
import ssl
import sys
import threading
import time
from pathlib import Path

import paho.mqtt.client as mqtt

# Capteurs simulés : type -> (unité, minimum normal, maximum normal)
SENSOR_TYPES = {
    "GAS": ("ppm", 200.0, 400.0),
    "SMOKE": ("ppm", 0.0, 50.0),
    "TEMPERATURE": ("C", 20.0, 26.0),
    "HUMIDITY": ("%", 30.0, 60.0),
    "MOTION": ("bool", 0.0, 1.0),
}

# Préfixe du sensorId pour chaque type (gas-101, smoke-101, temp-101...)
SENSOR_PREFIX = {
    "GAS": "gas",
    "SMOKE": "smoke",
    "TEMPERATURE": "temp",
    "HUMIDITY": "hum",
    "MOTION": "motion",
}

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CAFILE = REPO_ROOT / "infra" / "mqtt" / "certs" / "ca.pem"
DEVICE_ACCOUNT = "sbe-device"


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


# Nombre de cycles pour passer de la valeur normale à la valeur d'alerte
RAMP_STEPS = 30


def ramp(step, start, end):
    """Monte progressivement de start à end en RAMP_STEPS cycles, puis reste à end."""
    progress = min(step / RAMP_STEPS, 1.0)
    return start + (end - start) * progress


# ---------- Actionneurs simulés (contrat 9 de l'étape 2) ----------
# Le backend publie ses commandes sur smartbuilding/{bâtiment}/{salle}/cmd.
# Le simulateur joue le rôle de l'appareil : il applique la commande, l'affiche,
# et confirme le nouvel état sur smartbuilding/{bâtiment}/{salle}/actuator.

VENTILATION_DROP_PPM = 80   # baisse du gaz par cycle quand la ventilation tourne
VENTILATION_DELAY = 8       # cycles avant que la ventilation fasse effet (le gaz monte encore un peu,
                            # ce qui laisse le temps à l'incident de passer de MEDIUM à HIGH)

_lock = threading.Lock()
actuators = {}        # room_id -> {actuator: state}
ventilation_on = {}   # room_id -> cycle auquel la ventilation a démarré
current_step = 0      # cycle en cours (mis à jour par la boucle principale)

ACTUATOR_LABELS = {
    "SIREN": "sirène",
    "VENTILATION": "ventilation",
    "GAS_VALVE": "vanne de gaz",
    "EMERGENCY_EXIT": "issue de secours",
}


def on_connect(client, userdata, flags, reason_code, properties):
    userdata["connack"] = reason_code
    if not reason_code.is_failure:
        building = userdata["building"]
        client.subscribe(f"smartbuilding/{building}/+/cmd", qos=1)
    userdata["connected"].set()


def on_message(client, userdata, msg):
    """Commande reçue du backend : l'appliquer, l'afficher, la confirmer."""
    try:
        parts = msg.topic.split("/")
        room_id = parts[2]
        command = json.loads(msg.payload.decode("utf-8"))
        actuator = command["actuator"]
        action = command["action"]
    except (IndexError, KeyError, ValueError):
        print(f"  !! Commande illisible sur {msg.topic} : {msg.payload!r}")
        return

    with _lock:
        actuators.setdefault(room_id, {})[actuator] = action
        if actuator == "VENTILATION":
            if action == "ON":
                ventilation_on.setdefault(room_id, current_step)
            else:
                ventilation_on.pop(room_id, None)

    origin = "réaction automatique" if command.get("incidentId") else "commande manuelle"
    print(f"  >>> Salle {room_id.removeprefix('room-')} : "
          f"{ACTUATOR_LABELS.get(actuator, actuator)} {action} ({origin})")

    confirmation = {"actuator": actuator, "state": action, "timestamp": int(time.time() * 1000)}
    client.publish(f"smartbuilding/{parts[1]}/{room_id}/actuator", json.dumps(confirmation), qos=1)


def normal_value(sensor_type):
    unit, low, high = SENSOR_TYPES[sensor_type]
    if sensor_type == "MOTION":
        return 1.0 if random.random() < 0.3 else 0.0
    return random.uniform(low, high)


def scenario_value(sensor_type, scenario, step, room_id=None):
    """Valeur pour la salle touchée par le scénario (la première salle)."""
    noise = random.uniform(-0.03, 0.03)  # ±3 % pour que la courbe paraisse réelle

    if scenario == "gas-leak" and sensor_type == "GAS":
        with _lock:
            started = ventilation_on.get(room_id)
        if started is not None and step >= started + VENTILATION_DELAY:
            # La ventilation fait effet : le gaz redescend progressivement vers la normale
            effect = started + VENTILATION_DELAY
            peak = ramp(effect, 300.0, 2000.0)
            return max(320.0, peak - VENTILATION_DROP_PPM * (step - effect)) * (1 + noise)
        return ramp(step, 300.0, 2000.0) * (1 + noise)

    if scenario == "fire":
        if sensor_type == "SMOKE":
            return ramp(step, 20.0, 800.0) * (1 + noise)
        if sensor_type == "TEMPERATURE":
            return ramp(step, 24.0, 70.0) * (1 + noise)
        if sensor_type == "GAS":
            return ramp(step, 300.0, 700.0) * (1 + noise)

    return normal_value(sensor_type)


def build_message(sensor_type, room_number, value):
    unit = SENSOR_TYPES[sensor_type][0]
    if sensor_type == "MOTION":
        value = 1.0 if value >= 0.5 else 0.0
    return {
        "sensorId": f"{SENSOR_PREFIX[sensor_type]}-{room_number}",
        "type": sensor_type,
        "value": round(value, 1),
        "unit": unit,
        "timestamp": int(time.time() * 1000),
    }


def parse_args():
    parser = argparse.ArgumentParser(description="Simulateur de capteurs MQTT")
    parser.add_argument("--host", default="localhost", help="adresse du broker (défaut : localhost)")
    parser.add_argument("--port", type=int, default=None,
                        help="port du broker (défaut : 8883 avec --tls, 1883 sinon)")
    parser.add_argument("--username", default=None,
                        help=f"compte MQTT (défaut : {DEVICE_ACCOUNT} si un mot de passe est disponible)")
    parser.add_argument("--password", default=None,
                        help="mot de passe MQTT (défaut : MQTT_DEVICE_PASSWORD, variable ou .env). "
                             "Préférer le .env : un mot de passe tapé ici reste dans l'historique")
    parser.add_argument("--tls", action="store_true", help="connexion chiffrée MQTTS (étape 3)")
    parser.add_argument("--cafile", default=str(DEFAULT_CAFILE),
                        help="certificat de la CA du projet pour vérifier le broker (défaut : infra/mqtt/certs/ca.pem)")
    parser.add_argument("--building", default="bld-01", help="identifiant du bâtiment (défaut : bld-01)")
    parser.add_argument("--rooms", default="101,102,201",
                        help="numéros des salles, séparés par des virgules (défaut : 101,102,201)")
    parser.add_argument("--scenario", choices=["normal", "gas-leak", "fire"], default="normal",
                        help="scénario à simuler dans la première salle (défaut : normal)")
    parser.add_argument("--interval", type=float, default=2.0,
                        help="secondes entre deux envois (défaut : 2)")
    parser.add_argument("--count", type=int, default=0,
                        help="nombre de cycles puis arrêt (défaut : 0 = sans fin)")
    args = parser.parse_args()
    if args.port is None:
        args.port = 8883 if args.tls else 1883
    if args.password is None:
        args.password = os.environ.get("MQTT_DEVICE_PASSWORD") or None
    if args.username is None and args.password:
        args.username = DEVICE_ACCOUNT
    return args


def connect(client, args):
    """Se connecte et attend la réponse du broker. Quitte avec un message clair en cas d'échec."""
    if args.username:
        client.username_pw_set(args.username, args.password)
    if args.tls:
        if not Path(args.cafile).exists():
            sys.exit(f"Certificat de la CA introuvable : {args.cafile}\n"
                     "Génère-le avec infra/mqtt/gen-certs.ps1 (ou .sh), ou indique --cafile.")
        client.tls_set(ca_certs=args.cafile)
    try:
        client.connect(args.host, args.port, keepalive=60)
    except ssl.SSLCertVerificationError as error:
        sys.exit(f"Certificat du broker refusé ({error.verify_message}).\n"
                 f"Vérifie --cafile et que --host ({args.host}) figure dans le certificat (localhost, 127.0.0.1...).")
    except ssl.SSLError as error:
        sys.exit(f"Échec TLS avec {args.host}:{args.port} ({error}). Le port est-il bien le port TLS (8883) ?")
    except OSError as error:
        sys.exit(f"Impossible de joindre le broker {args.host}:{args.port} ({error}).\n"
                 "Vérifie que HiveMQ tourne : docker ps")

    client.loop_start()
    userdata = client.user_data_get()
    if not userdata["connected"].wait(15):
        client.loop_stop()
        sys.exit(f"Pas de réponse du broker {args.host}:{args.port} "
                 "(port TLS sans --tls, ou l'inverse ?)")
    reason = userdata["connack"]
    if reason.is_failure:
        client.loop_stop()
        if str(reason) in ("Bad user name or password", "Not authorized"):
            if not args.username:
                sys.exit(f"Broker : {reason}. Le broker exige un compte : mets MQTT_DEVICE_PASSWORD dans le .env "
                         "à la racine du dépôt (ou utilise --username / --password).")
            sys.exit(f"Broker : {reason}. Mot de passe refusé pour {args.username} : il doit être celui du .env "
                     "qui a servi à générer credentials.xml (infra/mqtt/gen_credentials.py).")
        sys.exit(f"Broker : connexion refusée ({reason}).")


def main():
    load_env_file(REPO_ROOT / ".env")
    args = parse_args()
    rooms = [r.strip() for r in args.rooms.split(",") if r.strip()]
    if not rooms:
        sys.exit("Erreur : il faut au moins une salle dans --rooms.")

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"sbe-simulator-{random.randint(1000, 9999)}",
                         userdata={"building": args.building, "connected": threading.Event(), "connack": None})
    client.on_connect = on_connect   # s'abonne aux commandes des actionneurs
    client.on_message = on_message

    connect(client, args)
    mode = ("TLS" if args.tls else "sans chiffrement") + (f", compte {args.username}" if args.username else ", anonyme")
    print(f"Connecté à {args.host}:{args.port} ({mode}), scénario '{args.scenario}', "
          f"salles {', '.join(rooms)}. Ctrl+C pour arrêter.")

    global current_step
    step = 0
    try:
        while args.count == 0 or step < args.count:
            with _lock:
                current_step = step
            for index, room_number in enumerate(rooms):
                room_id = f"room-{room_number}"
                for sensor_type in SENSOR_TYPES:
                    if index == 0 and args.scenario != "normal":
                        value = scenario_value(sensor_type, args.scenario, step, room_id)
                    else:
                        value = normal_value(sensor_type)

                    message = build_message(sensor_type, room_number, value)
                    topic = f"smartbuilding/{args.building}/{room_id}/{sensor_type}"
                    result = client.publish(topic, json.dumps(message), qos=1)
                    result.wait_for_publish(timeout=5)

                    if sensor_type in ("GAS", "SMOKE", "TEMPERATURE"):
                        print(f"  {topic}  {message['value']} {message['unit']}")
            step += 1
            print(f"Cycle {step} envoyé.")
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nArrêt demandé.")
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
