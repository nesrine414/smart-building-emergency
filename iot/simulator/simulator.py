"""
Simulateur de capteurs pour le projet Smart Building Emergency.

Publie des mesures simulées (gaz, fumée, température, humidité, mouvement)
sur le broker MQTT, au format convenu dans le plan de la démo (contrat 3) :

    topic   : smartbuilding/{buildingId}/{roomId}/{type}
    message : {"sensorId": "...", "type": "GAS", "value": 412.5,
               "unit": "ppm", "timestamp": 1762354800000}

Exemples :
    python simulator.py                         # scénario normal, toutes les 2 s
    python simulator.py --scenario gas-leak     # fuite de gaz dans la 1re salle
    python simulator.py --scenario fire --count 20
    python simulator.py --host 192.168.1.20 --interval 1
"""

import argparse
import json
import random
import sys
import time

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

# Nombre de cycles pour passer de la valeur normale à la valeur d'alerte
RAMP_STEPS = 30


def ramp(step, start, end):
    """Monte progressivement de start à end en RAMP_STEPS cycles, puis reste à end."""
    progress = min(step / RAMP_STEPS, 1.0)
    return start + (end - start) * progress


def normal_value(sensor_type):
    unit, low, high = SENSOR_TYPES[sensor_type]
    if sensor_type == "MOTION":
        return 1.0 if random.random() < 0.3 else 0.0
    return random.uniform(low, high)


def scenario_value(sensor_type, scenario, step):
    """Valeur pour la salle touchée par le scénario (la première salle)."""
    noise = random.uniform(-0.03, 0.03)  # ±3 % pour que la courbe paraisse réelle

    if scenario == "gas-leak" and sensor_type == "GAS":
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
    parser.add_argument("--port", type=int, default=1883, help="port du broker (défaut : 1883)")
    parser.add_argument("--building", default="bld-01", help="identifiant du bâtiment (défaut : bld-01)")
    parser.add_argument("--rooms", default="101,102,201",
                        help="numéros des salles, séparés par des virgules (défaut : 101,102,201)")
    parser.add_argument("--scenario", choices=["normal", "gas-leak", "fire"], default="normal",
                        help="scénario à simuler dans la première salle (défaut : normal)")
    parser.add_argument("--interval", type=float, default=2.0,
                        help="secondes entre deux envois (défaut : 2)")
    parser.add_argument("--count", type=int, default=0,
                        help="nombre de cycles puis arrêt (défaut : 0 = sans fin)")
    return parser.parse_args()


def main():
    args = parse_args()
    rooms = [r.strip() for r in args.rooms.split(",") if r.strip()]
    if not rooms:
        sys.exit("Erreur : il faut au moins une salle dans --rooms.")

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"sbe-simulator-{random.randint(1000, 9999)}")

    try:
        client.connect(args.host, args.port, keepalive=60)
    except OSError as error:
        sys.exit(f"Impossible de joindre le broker {args.host}:{args.port} ({error}).\n"
                 "Vérifie que HiveMQ tourne : docker ps")

    client.loop_start()
    print(f"Connecté à {args.host}:{args.port}, scénario '{args.scenario}', "
          f"salles {', '.join(rooms)}. Ctrl+C pour arrêter.")

    step = 0
    try:
        while args.count == 0 or step < args.count:
            for index, room_number in enumerate(rooms):
                room_id = f"room-{room_number}"
                for sensor_type in SENSOR_TYPES:
                    if index == 0 and args.scenario != "normal":
                        value = scenario_value(sensor_type, args.scenario, step)
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
