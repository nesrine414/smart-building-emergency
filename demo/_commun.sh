#!/usr/bin/env bash
# Fonctions communes des scripts de démonstration (étape 4, personne 1), version Linux/macOS.
# Ne pas lancer seul : incendie.sh, fuite-gaz.sh et retour-normal.sh le chargent.
# Dernier argument d'un script : (rien) broker en ligne, --local broker de ce PC,
# --via-serveur plan B lancé sur le serveur par SSH.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
SIMULATOR="$ROOT/iot/simulator/simulator.py"

env_value() {
  local name="$1" value="${!1:-}"
  if [[ -z "$value" && -f "$ENV_FILE" ]]; then
    value="$(grep -E "^[[:space:]]*${name}=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//' || true)"
  fi
  printf '%s' "$value"
}

stop_demo() { printf '\nERREUR : %s\n' "$*" >&2; exit 1; }

find_python() {
  local candidate
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null && "$candidate" -c 'import paho.mqtt' 2>/dev/null; then
      echo "$candidate"
      return
    fi
  done
  stop_demo "Python avec paho-mqtt introuvable : pip install -r iot/simulator/requirements.txt"
}

# start_scenario SCENARIO SALLES CYCLES [--local|--via-serveur]
start_scenario() {
  local scenario="$1" rooms="$2" count="$3" mode="${4:-}"
  [[ -f "$ENV_FILE" ]] || stop_demo "fichier .env absent ($ENV_FILE) : copie le .env de l'équipe"
  [[ -n "$(env_value MQTT_DEVICE_PASSWORD)" ]] || stop_demo "MQTT_DEVICE_PASSWORD vide dans .env"
  local common=(--tls --rooms "$rooms" --scenario "$scenario" --interval 1 --count "$count")

  if [[ "$mode" == "--via-serveur" ]]; then
    local target key
    target="$(env_value SBE_SSH_TARGET)"
    key="$(env_value SBE_SSH_KEY)"
    [[ -n "$target" ]] || stop_demo "SBE_SSH_TARGET vide dans .env (ex. sbe@13.38.0.1)"
    local ssh_args=(-t)
    [[ -n "$key" ]] && ssh_args+=(-i "$key")
    echo "Plan B : scénario '$scenario' lancé sur le serveur ($target), salles $rooms"
    # shellcheck disable=SC2029  # commande construite ici, jouée sur le serveur
    ssh "${ssh_args[@]}" "$target" \
      "cd /opt/sbe/smart-building-emergency && /opt/sbe/venv/bin/python iot/simulator/simulator.py --host localhost ${common[*]}"
    return
  fi

  local host ca hint
  if [[ "$mode" == "--local" ]]; then
    host="localhost"; ca="$ROOT/infra/mqtt/certs/ca.pem"; hint="bash infra/mqtt/gen-certs.sh"
  elif [[ -z "$mode" ]]; then
    host="$(env_value MQTT_BROKER_PUBLIC_HOST)"
    [[ -n "$host" ]] || stop_demo "MQTT_BROKER_PUBLIC_HOST vide dans .env (ex. mqtt.sbe-supcom.me)"
    ca="$ROOT/infra/mqtt/certs-serveur/ca.pem"; hint="bash deploy/fetch-ca.sh"
  else
    stop_demo "option inconnue : $mode (attendu : --local ou --via-serveur)"
  fi
  [[ -f "$ca" ]] || stop_demo "certificat de la CA absent : $ca ($hint)"
  local python
  python="$(find_python)"
  echo "Scénario '$scenario' vers $host:8883, salles $rooms, $count cycles de 1 s (Ctrl+C pour arrêter)"
  if ! "$python" "$SIMULATOR" --host "$host" --cafile "$ca" "${common[@]}"; then
    stop_demo "le simulateur s'est arrêté en erreur. Si le port 8883 est bloqué sur ce réseau : relance avec --via-serveur"
  fi
  echo "Scénario terminé."
}
