#!/usr/bin/env bash
# Retour au calme : valeurs normales dans les trois salles (l'admin clôt ensuite l'incident).
#     bash demo/retour-normal.sh              # vers le broker en ligne (MQTT_BROKER_PUBLIC_HOST)
#     bash demo/retour-normal.sh --local      # vers le broker de ce PC, pour répéter
#     bash demo/retour-normal.sh --via-serveur  # plan B : lancé sur le serveur par SSH
set -euo pipefail
# shellcheck source=demo/_commun.sh
. "$(dirname "${BASH_SOURCE[0]}")/_commun.sh"
start_scenario normal 201,101,102 "${COUNT:-30}" "${1:-}"
