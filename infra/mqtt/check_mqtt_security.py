"""
Vérifie la sécurité du broker MQTT (critères de fin de l'étape 3, contrat 5).

Le script se connecte comme les vrais clients et contrôle, sur le port TLS :
  1. qu'un client sans identifiant est refusé ;
  2. qu'un mauvais mot de passe est refusé ;
  3. que chaque compte (sbe-backend, sbe-device, sbe-ml) peut faire ce que le contrat 5 autorise,
     message reçu de bout en bout à l'appui ;
  4. qu'il ne peut rien faire d'autre : une publication interdite est refusée (code 135) et HiveMQ
     déconnecte le client fautif ;
  5. l'état du port 1883 (encore ouvert pendant la transition, ou fermé).

Il utilise MQTT 5, qui renvoie un code de refus précis (135 = non autorisé) pour chaque
publication et chaque abonnement, au lieu de couper la connexion comme MQTT 3.1.1.

Utilisation, depuis la racine du dépôt, broker démarré :
    python infra/mqtt/check_mqtt_security.py
    python infra/mqtt/check_mqtt_security.py --host localhost --port 8883 --cafile infra/mqtt/certs/ca.pem

Code de sortie 0 si tout est conforme, 1 sinon.
"""

import argparse
import os
import socket
import ssl
import sys
import threading
import time
import uuid
from pathlib import Path

import paho.mqtt.client as mqtt
from paho.mqtt.subscribeoptions import SubscribeOptions

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent
TIMEOUT = 8.0

ACCOUNTS = {
    "sbe-backend": "MQTT_BACKEND_PASSWORD",
    "sbe-device": "MQTT_DEVICE_PASSWORD",
    "sbe-ml": "MQTT_ML_PASSWORD",
}


def load_env_file(path):
    """Charge KEY=VALUE depuis un fichier .env sans écraser les variables déjà définies."""
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip().strip("\"'")
        os.environ.setdefault(key.strip(), value)


class BrokerUnreachable(Exception):
    """Le broker n'a pas répondu (arrêté, mauvais port, TLS) : rien n'a été testé."""


class Probe:
    """Client MQTT 5 synchrone, juste ce qu'il faut pour les tests."""

    def __init__(self, args, username=None, password=None, tls=True, port=None):
        self.args = args
        self.port = port or args.port
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id=f"sbe-check-{uuid.uuid4().hex[:8]}",
                                  protocol=mqtt.MQTTv5)
        if username:
            self.client.username_pw_set(username, password)
        if tls:
            self.client.tls_set(ca_certs=str(args.cafile))
        self.connected = threading.Event()
        self.connack = None
        self.acks = {}           # mid -> (Event, reason codes)
        self.received = []       # (topic, payload)
        self.got_message = threading.Event()
        self._lock = threading.Lock()
        self.client.on_connect = self._on_connect
        self.client.on_publish = self._on_ack
        self.client.on_subscribe = self._on_subscribe
        self.client.on_message = self._on_message

    # --- callbacks ---
    def _on_connect(self, client, userdata, flags, reason_code, properties):
        self.connack = reason_code
        self.connected.set()

    def _pending(self, mid):
        with self._lock:
            return self.acks.setdefault(mid, [threading.Event(), None])

    def _on_ack(self, client, userdata, mid, reason_code, properties):
        entry = self._pending(mid)
        entry[1] = [reason_code]
        entry[0].set()

    def _on_subscribe(self, client, userdata, mid, reason_codes, properties):
        entry = self._pending(mid)
        entry[1] = list(reason_codes)
        entry[0].set()

    def _on_message(self, client, userdata, msg):
        self.received.append((msg.topic, msg.payload.decode("utf-8", "replace")))
        self.got_message.set()

    # --- actions ---
    def connect(self):
        """
        Renvoie (True, code) si le broker accepte, (False, code de refus) s'il refuse.
        Lève BrokerUnreachable si on n'a même pas parlé au broker : ce n'est PAS un refus.
        """
        try:
            self.client.connect(self.args.host, self.port, keepalive=30)
        except ssl.SSLError as error:
            raise BrokerUnreachable(f"erreur TLS sur le port {self.port} : {error}") from None
        except OSError as error:
            raise BrokerUnreachable(f"port {self.port} injoignable : {error}") from None
        self.client.loop_start()
        if not self.connected.wait(TIMEOUT):
            self.close()
            raise BrokerUnreachable(f"pas de réponse MQTT sur le port {self.port}")
        if self.connack.is_failure:
            return False, str(self.connack)
        return True, str(self.connack)

    def _wait(self, mid):
        entry = self._pending(mid)
        if not entry[0].wait(TIMEOUT):
            return None
        return entry[1]

    def publish(self, topic, payload="{}"):
        """True si le broker accepte la publication (PUBACK < 128), False s'il refuse."""
        info = self.client.publish(topic, payload, qos=1)
        codes = self._wait(info.mid)
        if codes is None:
            return None
        return not codes[0].is_failure

    def subscribe(self, topic):
        """True si l'abonnement est accepté (SUBACK < 128), False s'il est refusé."""
        result, mid = self.client.subscribe(topic, options=SubscribeOptions(qos=1))
        codes = self._wait(mid)
        if codes is None:
            return None
        return not codes[0].is_failure

    def close(self):
        try:
            self.client.disconnect()
        finally:
            self.client.loop_stop()


class Report:
    def __init__(self):
        self.failures = 0

    def check(self, label, ok, detail=""):
        mark = "OK  " if ok else "ÉCHEC"
        if not ok:
            self.failures += 1
        print(f"  [{mark}] {label}" + (f"  ({detail})" if detail else ""))


def port_open(host, port):
    try:
        with socket.create_connection((host, port), timeout=2):
            return True
    except OSError:
        return False


def run(args):
    try:
        return run_checks(args)
    except BrokerUnreachable as error:
        print(f"\nBroker injoignable : {error}")
        print("Rien n'a pu être vérifié. HiveMQ tourne-t-il (docker ps, docker logs smart-building-hivemq) ?")
        return 1


def run_checks(args):
    report = Report()
    passwords = {account: os.environ.get(var, "") for account, var in ACCOUNTS.items()}
    missing = [ACCOUNTS[a] for a, p in passwords.items() if not p]
    if missing:
        sys.exit("Mots de passe absents (.env) : " + ", ".join(missing))
    if not Path(args.cafile).exists():
        sys.exit(f"Certificat de la CA introuvable : {args.cafile} (lance gen-certs d'abord)")

    print(f"Broker {args.host}:{args.port} (TLS, CA {args.cafile})\n")
    b, r = "bld-test", f"room-{uuid.uuid4().hex[:4]}"   # salle unique : n'interfère pas avec la démo

    print("1. Authentification")
    probe = Probe(args)
    ok, detail = probe.connect()
    report.check("client sans identifiant refusé", not ok, detail)
    probe.close()

    probe = Probe(args, "sbe-device", "mauvais-mot-de-passe")
    ok, detail = probe.connect()
    report.check("mauvais mot de passe refusé", not ok, detail)
    probe.close()

    clients = {}
    for account in ACCOUNTS:
        probe = Probe(args, account, passwords[account])
        ok, detail = probe.connect()
        report.check(f"{account} accepté en TLS", ok, detail)
        if not ok:
            print("\nArrêt : un compte valide est refusé. Vérifie credentials.xml (gen_credentials.py) et le .env.")
            return 1
        clients[account] = probe
    backend, device, ml = clients["sbe-backend"], clients["sbe-device"], clients["sbe-ml"]

    print("\n2. Abonnements (contrat 5)")
    report.check("sbe-backend peut s'abonner à smartbuilding/#", backend.subscribe("smartbuilding/#") is True)
    report.check("sbe-device peut s'abonner à .../cmd", device.subscribe(f"smartbuilding/{b}/+/cmd") is True)
    report.check("sbe-device ne peut PAS s'abonner à smartbuilding/#", device.subscribe("smartbuilding/#") is False)
    report.check("sbe-ml peut s'abonner aux mesures", all(
        ml.subscribe(f"smartbuilding/+/+/{t}") is True for t in ("GAS", "SMOKE", "TEMPERATURE", "HUMIDITY")))
    report.check("sbe-ml ne peut PAS s'abonner à .../cmd", ml.subscribe("smartbuilding/+/+/cmd") is False)

    print("\n3. Publications autorisées, reçues de bout en bout")

    def delivered(receiver, topic, action):
        """Lance action() et attend que receiver reçoive un message sur topic."""
        receiver.got_message.clear()
        receiver.received.clear()
        result = action()
        deadline = time.time() + TIMEOUT
        while time.time() < deadline:
            if any(t == topic for t, _ in receiver.received):
                return result, True
            receiver.got_message.wait(0.2)
            receiver.got_message.clear()
        return result, False

    gas_topic = f"smartbuilding/{b}/{r}/GAS"
    cmd_topic = f"smartbuilding/{b}/{r}/cmd"
    risk_topic = f"smartbuilding/{b}/{r}/RISK"
    actuator_topic = f"smartbuilding/{b}/{r}/actuator"

    accepted, got = delivered(ml, gas_topic, lambda: device.publish(gas_topic, '{"value":300}'))
    report.check("sbe-device publie une mesure GAS", accepted is True)
    report.check("  ... reçue par sbe-ml", got)
    report.check("  ... reçue par sbe-backend", any(t == gas_topic for t, _ in backend.received)
                 or delivered(backend, gas_topic, lambda: device.publish(gas_topic, '{"value":301}'))[1])

    accepted, got = delivered(device, cmd_topic,
                              lambda: backend.publish(cmd_topic, '{"actuator":"SIREN","action":"ON"}'))
    report.check("sbe-backend publie une commande .../cmd", accepted is True)
    report.check("  ... reçue par sbe-device", got)

    accepted, got = delivered(backend, actuator_topic,
                              lambda: device.publish(actuator_topic, '{"actuator":"SIREN","state":"ON"}'))
    report.check("sbe-device publie une confirmation .../actuator", accepted is True)
    report.check("  ... reçue par sbe-backend", got)

    accepted, got = delivered(backend, risk_topic, lambda: ml.publish(risk_topic, '{"value":0.1}'))
    report.check("sbe-ml publie un score RISK", accepted is True)
    report.check("  ... reçu par sbe-backend", got)

    for probe in clients.values():
        probe.close()

    print("\n4. Publications interdites (refusées, puis le client est déconnecté par le broker)")

    def denied(account, topic, retain=False):
        # Une connexion neuve par essai : HiveMQ coupe le client après une publication interdite.
        probe = Probe(args, account, passwords[account])
        ok, detail = probe.connect()   # BrokerUnreachable remonte jusqu'à run()
        if not ok:
            return False, f"connexion impossible : {detail}"
        info = probe.client.publish(topic, '{"value":1}', qos=1, retain=retain)
        codes = probe._wait(info.mid)
        probe.close()
        if codes is None:
            return False, "pas de réponse"
        return codes[0].is_failure, str(codes[0])

    for account, topic, label in [
        ("sbe-device", cmd_topic, "sbe-device ne peut PAS publier de commande .../cmd"),
        ("sbe-device", risk_topic, "sbe-device ne peut PAS publier de score RISK"),
        ("sbe-backend", gas_topic, "sbe-backend ne peut PAS publier de fausse mesure"),
        ("sbe-ml", gas_topic, "sbe-ml ne peut PAS publier de mesure GAS"),
        ("sbe-ml", cmd_topic, "sbe-ml ne peut PAS publier de commande"),
    ]:
        ok, detail = denied(account, topic)
        report.check(label, ok, detail)
    ok, detail = denied("sbe-device", gas_topic, retain=True)
    report.check("message retained refusé (pas de fausse valeur laissée sur le broker)", ok, detail)

    print(f"\n5. Port en clair {args.plain_port}")
    if port_open(args.host, args.plain_port):
        probe = Probe(args, tls=False, port=args.plain_port)
        try:
            ok, detail = probe.connect()
        except BrokerUnreachable as error:
            ok, detail = True, str(error)
        probe.close()
        report.check(f"port {args.plain_port} ouvert (transition) mais refuse les anonymes", not ok, detail)
        print(f"         Quand tous les clients sont sur {args.port}, passer à config-tls-only.xml.")
    else:
        print(f"  [OK  ] port {args.plain_port} fermé : seul le TLS est accepté")

    print()
    if report.failures:
        print(f"{report.failures} contrôle(s) en échec.")
        return 1
    print("Tous les contrôles sont conformes au contrat 5.")
    return 0


def main():
    load_env_file(Path(os.environ.get("ENV_FILE", REPO_ROOT / ".env")))
    parser = argparse.ArgumentParser(description="Contrôle de la sécurité du broker MQTT")
    parser.add_argument("--host", default="localhost", help="nom du broker, tel qu'écrit dans le certificat")
    parser.add_argument("--port", type=int, default=8883, help="port TLS (défaut : 8883)")
    parser.add_argument("--plain-port", type=int, default=1883, help="port en clair à contrôler (défaut : 1883)")
    parser.add_argument("--cafile", type=Path, default=HERE / "certs" / "ca.pem", help="certificat de la CA")
    sys.exit(run(parser.parse_args()))


if __name__ == "__main__":
    main()
