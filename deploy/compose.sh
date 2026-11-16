#!/usr/bin/env bash
# Raccourci pour Docker Compose avec les trois fichiers du serveur en ligne (étape 4) :
#     bash deploy/compose.sh up -d
#     bash deploy/compose.sh ps
#     bash deploy/compose.sh logs -f sbe-ml
#     bash deploy/compose.sh run --rm sbe-ml python train.py --notes "serveur"
# Exporte l'UID et le GID de l'utilisateur courant (sbe) pour le service sbe-ml.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
SBE_UID="$(id -u)"
SBE_GID="$(id -g)"
export SBE_UID SBE_GID
exec docker compose -f docker-compose.yml -f docker-compose.secure.yml -f docker-compose.prod.yml "$@"
