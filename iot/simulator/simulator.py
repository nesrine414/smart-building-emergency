
"""
Simulateur de capteurs pour le projet Smart Building Emergency.

Publie des mesures simulées (gaz, fumée, température, humidité, mouvement)
sur le broker MQTT :

    topic   : smartbuilding/{buildingId}/{roomId}/{type}
    message : {"sensorId": "...", "type": "GAS", "value": 412.5,
               "unit": "ppm", "timestamp": 1762354800000}

Il joue aussi le rôle des actionneurs :
- Reçoit les commandes du backend sur smartbuilding/{buildingId}/{roomId}/cmd.
- Affiche les commandes reçues.
- Confirme leur application sur smartbuilding/{buildingId}/{roomId}/actuator.

Sécurité :
- Compte MQTT : sbe-device.
- Mot de passe : MQTT_DEVICE_PASSWORD dans l'environnement ou .env.
- Avec --tls : connexion chiffrée sur le port 8883.
- Vérification du certificat avec infra/mqtt/certs/ca.pem.

Exemples :
    py simulator.py
    py simulator.py --scenario gas-leak
    py simulator.py --scenario fire --count 20
    py simulator.py --tls --scenario gas-leak
    py simulator.py --tls --rooms 201,101,102 --scenario fire
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

# Préfixe du sensorId pour chaque type
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

# Nombre de cycles pour passer progressivement à une valeur d'alerte.
RAMP_STEPS = 30


def load_env_file(path):
    """Charge KEY=VALUE depuis .env sans écraser les variables existantes."""
    if not path.exists():
        return

    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()

        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


def ramp(step, start, end):
    """Monte progressivement de start à end en RAMP_STEPS cycles."""
    progress = min(step / RAMP_STEPS, 1.0)
    return start + (end - start) * progress


# ---------- Actionneurs simulés ----------

VENTILATION_DROP_PPM = 80
VENTILATION_DELAY = 8

_lock = threading.Lock()
actuators = {}
ventilation_on = {}
current_step = 0

ACTUATOR_LABELS = {
    "SIREN": "sirène",
    "VENTILATION": "ventilation",
    "GAS_VALVE": "vanne de gaz",
    "EMERGENCY_EXIT": "issue de secours",
}


def on_connect(client, userdata, flags, reason_code, properties):
    """S'abonne aux commandes lorsque la connexion est acceptée."""
    userdata["connack"] = reason_code

    if not reason_code.is_failure:
        building = userdata["building"]
        client.subscribe(f"smartbuilding/{building}/+/cmd", qos=1)

    userdata["connected"].set()


def on_message(client, userdata, msg):
    """Applique une commande reçue et confirme le nouvel état."""
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

    origin = (
        "réaction automatique"
        if command.get("incidentId")
        else "commande manuelle"
    )

    print(
        f"  >>> Salle {room_id.removeprefix('room-')} : "
        f"{ACTUATOR_LABELS.get(actuator, actuator)} {action} ({origin})"
    )

    confirmation = {
        "actuator": actuator,
        "state": action,
        "timestamp": int(time.time() * 1000),
    }

    client.publish(
        f"smartbuilding/{parts[1]}/{room_id}/actuator",
        json.dumps(confirmation),
        qos=1,
    )


def normal_value(sensor_type):
    """Génère une valeur normale pour un capteur."""
    unit, low, high = SENSOR_TYPES[sensor_type]

    if sensor_type == "MOTION":
        return 1.0 if random.random() < 0.3 else 0.0

    return random.uniform(low, high)


def scenario_value(sensor_type, scenario, step, room_id=None):
    """Génère une valeur correspondant au scénario sélectionné."""
    noise = random.uniform(-0.03, 0.03)

    if scenario == "gas-leak" and sensor_type == "GAS":
        with _lock:
            started = ventilation_on.get(room_id)

        if started is not None and step >= started + VENTILATION_DELAY:
            effect = started + VENTILATION_DELAY
            peak = ramp(effect, 300.0, 2000.0)

            return max(
                320.0,
                peak - VENTILATION_DROP_PPM * (step - effect),
            ) * (1 + noise)

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
    """Construit le message JSON envoyé au broker."""
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
    """Définit et vérifie les arguments de la ligne de commande."""
    parser = argparse.ArgumentParser(
        description="Simulateur de capteurs MQTT"
    )

    parser.add_argument(
        "--host",
        default="mqtt.smartbuilding.local",
        help="adresse du broker (défaut : mqtt.smartbuilding.local)",
    )

    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help="port du broker (8883 avec --tls, 1883 sinon)",
    )

    parser.add_argument(
        "--username",
        default=None,
        help=f"compte MQTT (défaut : {DEVICE_ACCOUNT} si un mot de passe est disponible)",
    )

    parser.add_argument(
        "--password",
        default=None,
        help="mot de passe MQTT (défaut : MQTT_DEVICE_PASSWORD dans .env)",
    )

    parser.add_argument(
        "--tls",
        action="store_true",
        help="active la connexion chiffrée MQTTS",
    )

    parser.add_argument(
        "--cafile",
        default=str(DEFAULT_CAFILE),
        help="certificat CA utilisé pour vérifier le broker",
    )

    parser.add_argument(
        "--building",
        default="bld-01",
        help="identifiant du bâtiment (défaut : bld-01)",
    )

    parser.add_argument(
        "--rooms",
        default="101,102,201",
        help="numéros des salles séparés par des virgules",
    )

    parser.add_argument(
        "--scenario",
        choices=["normal", "gas-leak", "fire"],
        default="normal",
        help="scénario à simuler dans la première salle",
    )

    parser.add_argument(
        "--interval",
        type=float,
        default=2.0,
        help="secondes entre deux cycles",
    )

    parser.add_argument(
        "--count",
        type=int,
        default=0,
        help="nombre de cycles (0 = sans limite)",
    )

    args = parser.parse_args()

    if args.port is None:
        args.port = 8883 if args.tls else 1883

    if args.password is None:
        args.password = os.environ.get("MQTT_DEVICE_PASSWORD") or None

    if args.username is None and args.password:
        args.username = DEVICE_ACCOUNT

    return args


def connect(client, args):
    """Se connecte au broker et attend la réponse d'authentification."""

    if args.username:
        client.username_pw_set(args.username, args.password)

    if args.tls:
        if not Path(args.cafile).exists():
            sys.exit(
                f"Certificat CA introuvable : {args.cafile}\n"
                "Vérifie infra/mqtt/certs/ca.pem ou indique --cafile."
            )

        client.tls_set(
            ca_certs=args.cafile,
            cert_reqs=ssl.CERT_REQUIRED,
            tls_version=ssl.PROTOCOL_TLS_CLIENT,
        )

    try:
        client.connect(args.host, args.port, keepalive=60)

    except ssl.SSLCertVerificationError as error:
        sys.exit(
            f"Certificat du broker refusé : {error.verify_message}.\n"
            f"Vérifie --cafile et le nom {args.host}."
        )

    except ssl.SSLError as error:
        sys.exit(
            f"Échec TLS avec {args.host}:{args.port} : {error}.\n"
            "Vérifie que le port utilisé est bien le port TLS 8883."
        )

    except OSError as error:
        sys.exit(
            f"Impossible de joindre le broker {args.host}:{args.port} : {error}.\n"
            "Vérifie que HiveMQ fonctionne."
        )

    client.loop_start()

    userdata = client.user_data_get()

    if not userdata["connected"].wait(15):
        client.loop_stop()

        sys.exit(
            f"Pas de réponse du broker {args.host}:{args.port}.\n"
            "Vérifie la configuration TLS et le port utilisé."
        )

    reason = userdata["connack"]

    if reason.is_failure:
        client.loop_stop()

        if str(reason) in ("Bad user name or password", "Not authorized"):
            if not args.username:
                sys.exit(
                    "Le broker exige une authentification. "
                    "Vérifie MQTT_DEVICE_PASSWORD dans .env."
                )

            sys.exit(
                f"Authentification refusée pour {args.username}. "
                "Vérifie que le mot de passe correspond à celui utilisé "
                "pour générer credentials.xml."
            )

        sys.exit(f"Connexion refusée par le broker : {reason}")


def main():
    """Point d'entrée principal du simulateur."""
    load_env_file(REPO_ROOT / ".env")

    args = parse_args()

    rooms = [
        room.strip()
        for room in args.rooms.split(",")
        if room.strip()
    ]

    if not rooms:
        sys.exit("Erreur : il faut au moins une salle dans --rooms.")

    if args.interval <= 0:
        sys.exit("Erreur : --interval doit être strictement positif.")

    if args.count < 0:
        sys.exit("Erreur : --count ne peut pas être négatif.")

    if not args.password:
        sys.exit(
            "Mot de passe MQTT absent. Vérifie MQTT_DEVICE_PASSWORD "
            "dans le fichier .env à la racine du projet."
        )

    if args.username != DEVICE_ACCOUNT:
        sys.exit(
            f"Le simulateur doit utiliser le compte {DEVICE_ACCOUNT}."
        )

    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"sbe-simulator-{random.randint(1000, 9999)}",
        userdata={
            "building": args.building,
            "connected": threading.Event(),
            "connack": None,
        },
    )

    client.on_connect = on_connect
    client.on_message = on_message

    connect(client, args)

    mode = "TLS" if args.tls else "sans chiffrement"

    print(
        f"Connecté à {args.host}:{args.port} ({mode}), "
        f"compte {args.username}, scénario '{args.scenario}', "
        f"salles {', '.join(rooms)}."
    )

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
                        value = scenario_value(
                            sensor_type,
                            args.scenario,
                            step,
                            room_id,
                        )
                    else:
                        value = normal_value(sensor_type)

                    message = build_message(
                        sensor_type,
                        room_number,
                        value,
                    )

                    topic = (
                        f"smartbuilding/{args.building}/"
                        f"{room_id}/{sensor_type}"
                    )

                    result = client.publish(
                        topic,
                        json.dumps(message),
                        qos=1,
                    )

                    result.wait_for_publish(timeout=5)

                    if sensor_type in (
                        "GAS",
                        "SMOKE",
                        "TEMPERATURE",
                    ):
                        print(
                            f"  {topic}  "
                            f"{message['value']} {message['unit']}"
                        )

            step += 1
            print(f"Cycle {step} envoyé.")

            if args.count == 0 or step < args.count:
                time.sleep(args.interval)

    except KeyboardInterrupt:
        print("\nArrêt demandé.")

    finally:
        for action in (client.disconnect, client.loop_stop):
            try:
                action()
            except Exception:
                pass


if __name__ == "__main__":
    main()
