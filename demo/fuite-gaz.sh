#!/usr/bin/env bash
# Fuite de gaz dans la salle 101 : ventilation ouverte et vanne de gaz fermée, puis le gaz redescend.
#     bash demo/fuite-gaz.sh              # vers le broker en ligne (MQTT_BROKER_PUBLIC_HOST)
#     bash demo/fuite-gaz.sh --local      # vers le broker de ce PC, pour répéter
#     bash demo/fuite-gaz.sh --via-serveur  # plan B : lancé sur le serveur par SSH
set -euo pipefail
# shellcheck source=demo/_commun.sh
. "$(dirname "${BASH_SOURCE[0]}")/_commun.sh"
start_scenario gas-leak 101,102,201 "${COUNT:-150}" "${1:-}"
