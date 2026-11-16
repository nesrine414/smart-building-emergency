#!/usr/bin/env bash
# Incendie dans la salle 201 : la salle passe au rouge, sirène et porte de secours, score RISK qui monte.
#     bash demo/incendie.sh              # vers le broker en ligne (MQTT_BROKER_PUBLIC_HOST)
#     bash demo/incendie.sh --local      # vers le broker de ce PC, pour répéter
#     bash demo/incendie.sh --via-serveur  # plan B : lancé sur le serveur par SSH
set -euo pipefail
# shellcheck source=demo/_commun.sh
. "$(dirname "${BASH_SOURCE[0]}")/_commun.sh"
start_scenario fire 201,101,102 "${COUNT:-120}" "${1:-}"
