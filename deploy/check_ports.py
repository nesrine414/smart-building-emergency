"""
Contrôle, depuis l'extérieur, les noms et les ports du serveur en ligne (étape 4, personne 1).

À lancer depuis votre PC (pas depuis le serveur : il faut voir ce que voit Internet) :

    python deploy/check_ports.py sbe-supcom.me

1. Les cinq noms (www, admin, api, iam, mqtt) pointent tous vers la même IP.
2. Les ports 80, 443 et 8883 répondent.
3. Les ports internes sont fermés : 1883 (MQTT en clair), 8000 (WebSocket HiveMQ), 8080 et 9990
   (WildFly), 27017 (MongoDB), 11434 (Ollama).
4. Le port 22 est affiché pour information : il ne doit répondre que depuis vos IP.

Bibliothèque standard seulement. Code de sortie 0 si tout est conforme, 1 sinon.
"""

import argparse
import socket
import sys

NAMES = ("www", "admin", "api", "iam", "mqtt")
MUST_BE_OPEN = (80, 443, 8883)
MUST_BE_CLOSED = (1883, 8000, 8080, 9990, 27017, 11434)
INFO_ONLY = (22,)
PORT_ROLES = {22: "SSH", 80: "HTTP (redirection)", 443: "HTTPS", 8883: "MQTT TLS", 1883: "MQTT en clair",
              8000: "WebSocket HiveMQ", 8080: "WildFly", 9990: "admin WildFly", 27017: "MongoDB",
              11434: "Ollama"}


def resolve(name):
    try:
        return sorted({info[4][0] for info in socket.getaddrinfo(name, None, socket.AF_INET)})
    except socket.gaierror:
        return []


def is_open(ip, port, timeout):
    """Vrai si une connexion TCP aboutit. Refus ET silence (filtré) comptent comme fermé."""
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def run(args):
    failures = 0

    def check(ok, label):
        nonlocal failures
        print(f"  [{'OK  ' if ok else 'ÉCHEC'}] {label}")
        if not ok:
            failures += 1

    if args.ip:
        target = args.ip
        print(f"Cible imposée : {target} (noms DNS non vérifiés)\n")
    else:
        print(f"1. Noms du domaine {args.domain}")
        found = {}
        for prefix in NAMES:
            name = f"{prefix}.{args.domain}"
            ips = resolve(name)
            found[name] = ips
            check(bool(ips), f"{name:<28} -> {', '.join(ips) or 'introuvable'}")
        distinct = {ip for ips in found.values() for ip in ips}
        check(len(distinct) == 1, "les cinq noms pointent vers la même IP"
              + ("" if len(distinct) == 1 else f" (trouvé : {', '.join(sorted(distinct)) or 'aucune'})"))
        if not distinct:
            print("\nAucune IP : vérifie les enregistrements A chez Namecheap (propagation jusqu'à 48 h).")
            return 1
        target = sorted(distinct)[0]
        print()

    print(f"2. Ports qui doivent répondre ({target})")
    for port in args.expect_open:
        check(is_open(target, port, args.timeout), f"{port:>5}  {PORT_ROLES.get(port, '')}")

    print("\n3. Ports qui doivent rester fermés")
    for port in args.expect_closed:
        check(not is_open(target, port, args.timeout), f"{port:>5}  {PORT_ROLES.get(port, '')}")

    print("\n4. Pour information")
    for port in INFO_ONLY:
        state = "répond" if is_open(target, port, args.timeout) else "ne répond pas"
        print(f"  [INFO] {port:>5}  {PORT_ROLES[port]} : {state} depuis ce PC "
              "(doit répondre seulement depuis les IP autorisées dans le groupe de sécurité)")

    print()
    if failures:
        print(f"{failures} contrôle(s) en échec.")
        return 1
    print("Noms et ports conformes au plan de l'étape 4.")
    return 0


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Contrôle des noms et des ports du serveur en ligne")
    parser.add_argument("domain", nargs="?", default=None, help="domaine de l'équipe, sans www (ex. sbe-supcom.me)")
    parser.add_argument("--ip", default=None, help="tester cette IP directement, sans DNS")
    parser.add_argument("--timeout", type=float, default=3.0, help="secondes d'attente par port (défaut : 3)")
    parser.add_argument("--expect-open", type=int, nargs="+", default=list(MUST_BE_OPEN), metavar="PORT")
    parser.add_argument("--expect-closed", type=int, nargs="+", default=list(MUST_BE_CLOSED), metavar="PORT")
    args = parser.parse_args(argv)
    if not args.domain and not args.ip:
        parser.error("indique le domaine (ex. sbe-supcom.me) ou --ip")
    return args


if __name__ == "__main__":
    sys.exit(run(parse_args()))
