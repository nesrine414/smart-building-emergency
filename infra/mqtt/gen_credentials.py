"""
Génère le fichier credentials.xml de l'extension HiveMQ File RBAC (contrat 5 de l'étape 3).

Trois comptes, un par type de client, avec seulement les droits dont ils ont besoin :

    Compte        Peut publier                                   Peut s'abonner
    sbe-backend   smartbuilding/+/+/cmd                          smartbuilding/#
    sbe-device    mesures (GAS, SMOKE, ...) et .../actuator      smartbuilding/+/+/cmd
    sbe-ml        smartbuilding/+/+/RISK                         mesures

Les mots de passe sont lus dans le fichier .env à la racine du dépôt (ou dans l'environnement)
et ne sont jamais écrits en clair : chaque mot de passe est haché en PBKDF2-HMAC-SHA512 avec un
sel aléatoire, au format exact de l'outil officiel de l'extension :

    base64(sel) : itérations : base64(PBKDF2-HMAC-SHA512(mot de passe, sel, itérations, 64 octets))

(vérifié contre l'exemple officiel user1 / pass1 et contre l'outil java -jar de l'extension 4.6.19,
voir tests/test_gen_credentials.py).

Utilisation (depuis la racine du dépôt) :
    python infra/mqtt/gen_credentials.py --init-env    # crée .env et remplit les secrets vides
    python infra/mqtt/gen_credentials.py               # écrit infra/mqtt/rbac/credentials.xml
    python infra/mqtt/gen_credentials.py --check sbe-device   # vérifie le mot de passe du .env

Le fichier produit ne contient que des hachés, mais il reste dans le .gitignore : chacun le
régénère à partir du .env partagé.
"""

import argparse
import base64
import hashlib
import hmac
import os
import secrets
import string
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from xml.sax.saxutils import escape

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent
DEFAULT_ENV = REPO_ROOT / ".env"
DEFAULT_OUTPUT = HERE / "rbac" / "credentials.xml"

# OWASP recommande au moins 210 000 itérations pour PBKDF2-HMAC-SHA512 ; HiveMQ garde le résultat
# en cache 30 s, donc le coût n'est payé qu'à la connexion.
DEFAULT_ITERATIONS = 210_000
MIN_PASSWORD_LENGTH = 12

READING_TYPES = ["GAS", "SMOKE", "TEMPERATURE", "HUMIDITY", "MOTION"]

# Contrat 5 : (activité, topic) par rôle. Les publications sont refusées si elles sont "retained" :
# un appareil ne doit pas pouvoir laisser une fausse commande ou une fausse mesure sur le broker.
ROLES = {
    "backend": [("PUBLISH", "smartbuilding/+/+/cmd"),
                ("SUBSCRIBE", "smartbuilding/#")],
    "device": [("PUBLISH", f"smartbuilding/+/+/{t}") for t in READING_TYPES]
              + [("PUBLISH", "smartbuilding/+/+/actuator"),
                 ("SUBSCRIBE", "smartbuilding/+/+/cmd")],
    "ml": [("PUBLISH", "smartbuilding/+/+/RISK")]
          + [("SUBSCRIBE", f"smartbuilding/+/+/{t}") for t in READING_TYPES],
}

# compte MQTT -> (rôle, variable du .env qui contient son mot de passe)
ACCOUNTS = {
    "sbe-backend": ("backend", "MQTT_BACKEND_PASSWORD"),
    "sbe-device": ("device", "MQTT_DEVICE_PASSWORD"),
    "sbe-ml": ("ml", "MQTT_ML_PASSWORD"),
}

# Secrets que --init-env sait générer s'ils sont vides dans .env (longueur en caractères).
GENERATED_SECRETS = {
    "MQTT_BACKEND_PASSWORD": 24,
    "MQTT_DEVICE_PASSWORD": 24,
    "MQTT_ML_PASSWORD": 24,
    "MQTT_KEYSTORE_PASSWORD": 24,
    "MQTT_TRUSTSTORE_PASSWORD": 24,
    "JWT_SECRET": 48,
}


# ---------- Fichier .env ----------

def read_env_file(path):
    """Lit un fichier KEY=VALUE (lignes vides et # ignorées, guillemets retirés)."""
    values = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values


def get_secret(name, env_file_values):
    """Variable d'environnement d'abord, puis .env."""
    return os.environ.get(name) or env_file_values.get(name, "")


def random_secret(length):
    # Lettres et chiffres seulement : pas de caractère qui pose problème dans un .env,
    # une ligne de commande PowerShell ou un fichier XML.
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def init_env(env_path, example_path):
    """Crée .env à partir de .env.example si besoin, puis remplit les secrets vides."""
    if not env_path.exists():
        if not example_path.exists():
            sys.exit(f"Ni {env_path} ni {example_path} n'existent.")
        env_path.write_text(example_path.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
        print(f"Créé : {env_path} (copie de {example_path.name})")

    lines = env_path.read_text(encoding="utf-8").splitlines()
    filled = []
    for i, raw in enumerate(lines):
        stripped = raw.strip()
        if stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        key = key.strip()
        if key in GENERATED_SECRETS and value.strip() in ("", '""', "''"):
            lines[i] = f"{key}={random_secret(GENERATED_SECRETS[key])}"
            filled.append(key)
    # Fins de ligne Unix même sous Windows : lues sans surprise par Docker Compose, bash et PowerShell.
    env_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    if filled:
        print("Secrets générés : " + ", ".join(filled))
    else:
        print("Aucun secret vide : .env inchangé.")
    print("Partage ce .env avec l'équipe par un canal privé, jamais sur Git.")


# ---------- Hachage (format de l'extension File RBAC) ----------

def hash_password(password, iterations=DEFAULT_ITERATIONS, salt=None):
    """Renvoie la chaîne 'sel_b64:itérations:haché_b64' attendue par l'extension."""
    if salt is None:
        # Comme l'outil officiel : 32 caractères alphanumériques aléatoires.
        salt = random_secret(32)
    salt_bytes = salt.encode("utf-8")
    digest = hashlib.pbkdf2_hmac("sha512", password.encode("utf-8"), salt_bytes, iterations, dklen=64)
    return "{}:{}:{}".format(base64.b64encode(salt_bytes).decode("ascii"),
                             iterations,
                             base64.b64encode(digest).decode("ascii"))


def verify_password(password, stored):
    """Vérifie un mot de passe contre une chaîne 'sel:itérations:haché' (comme le fait HiveMQ)."""
    try:
        salt_b64, iterations, hash_b64 = stored.split(":")
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(hash_b64)
        digest = hashlib.pbkdf2_hmac("sha512", password.encode("utf-8"), salt, int(iterations),
                                     dklen=len(expected))
    except ValueError:
        return False
    return hmac.compare_digest(digest, expected)


# ---------- credentials.xml ----------

def build_credentials_xml(passwords, iterations=DEFAULT_ITERATIONS):
    """passwords : {compte: mot de passe en clair}. Renvoie le texte XML."""
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
           "<!--",
           f"  Généré par infra/mqtt/gen_credentials.py le {now}. Ne pas modifier à la main :",
           "  changer le mot de passe dans .env puis relancer le script (HiveMQ relit ce fichier",
           "  toutes les 60 s). Contrat 5 de l'étape 3.",
           "-->",
           "<file-rbac>",
           "    <users>"]
    for account, (role, _) in ACCOUNTS.items():
        out += ["        <user>",
                f"            <name>{escape(account)}</name>",
                f"            <password>{hash_password(passwords[account], iterations)}</password>",
                f"            <roles><id>{role}</id></roles>",
                "        </user>"]
    out += ["    </users>", "    <roles>"]
    for role, permissions in ROLES.items():
        out += ["        <role>", f"            <id>{role}</id>", "            <permissions>"]
        for activity, topic in permissions:
            out += ["                <permission>",
                    f"                    <topic>{escape(topic)}</topic>",
                    f"                    <activity>{activity}</activity>"]
            if activity == "PUBLISH":
                out.append("                    <retain>NOT_RETAINED</retain>")
            out.append("                </permission>")
        out += ["            </permissions>", "        </role>"]
    out += ["    </roles>", "</file-rbac>", ""]
    return "\n".join(out)


def collect_passwords(env_file_values):
    passwords, problems = {}, []
    for account, (_, variable) in ACCOUNTS.items():
        value = get_secret(variable, env_file_values)
        if not value:
            problems.append(f"{variable} est vide (mot de passe de {account})")
        elif len(value) < MIN_PASSWORD_LENGTH:
            problems.append(f"{variable} fait moins de {MIN_PASSWORD_LENGTH} caractères")
        passwords[account] = value
    if len(set(passwords.values())) < len(passwords):
        problems.append("deux comptes ont le même mot de passe : chaque compte doit avoir le sien")
    return passwords, problems


def check_account(account, credentials_path, env_file_values):
    """Vérifie que le mot de passe du .env correspond au haché de credentials.xml."""
    if account not in ACCOUNTS:
        sys.exit(f"Compte inconnu : {account} (attendu : {', '.join(ACCOUNTS)})")
    if not credentials_path.exists():
        sys.exit(f"{credentials_path} n'existe pas : lance d'abord le script sans --check.")
    root = ET.parse(credentials_path).getroot()
    for user in root.iter("user"):
        if user.findtext("name") == account:
            password = get_secret(ACCOUNTS[account][1], env_file_values)
            ok = verify_password(password, user.findtext("password", ""))
            print(f"{account} : {'mot de passe correct' if ok else 'mot de passe DIFFÉRENT du .env'}")
            return ok
    print(f"{account} absent de {credentials_path}")
    return False


def main(argv=None):
    parser = argparse.ArgumentParser(description="Génère credentials.xml pour HiveMQ File RBAC")
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV, help="fichier .env (défaut : racine du dépôt)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="fichier credentials.xml à écrire")
    parser.add_argument("--iterations", type=int, default=DEFAULT_ITERATIONS,
                        help=f"itérations PBKDF2 (défaut : {DEFAULT_ITERATIONS})")
    parser.add_argument("--init-env", action="store_true",
                        help="crée .env depuis .env.example et génère les secrets vides, puis s'arrête")
    parser.add_argument("--check", metavar="COMPTE", help="vérifie le mot de passe d'un compte et s'arrête")
    args = parser.parse_args(argv)

    if args.init_env:
        init_env(args.env_file, args.env_file.with_name(".env.example"))
        return 0

    env_file_values = read_env_file(args.env_file)

    if args.check:
        return 0 if check_account(args.check, args.output, env_file_values) else 1

    if args.iterations < 10_000:
        sys.exit("Moins de 10 000 itérations : trop faible pour un mot de passe stocké.")

    passwords, problems = collect_passwords(env_file_values)
    if problems:
        print("Impossible de générer credentials.xml :", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print("Astuce : python infra/mqtt/gen_credentials.py --init-env", file=sys.stderr)
        return 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(build_credentials_xml(passwords, args.iterations), encoding="utf-8")
    print(f"Écrit : {args.output}")
    for account, (role, _) in ACCOUNTS.items():
        print(f"  {account:<12} rôle {role}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
