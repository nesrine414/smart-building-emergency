#!/usr/bin/env bash
# Récupère le certificat de la CA du serveur en ligne, pour vérifier mqtt.<domaine> depuis ce PC.
# Utilise SBE_SSH_TARGET (ex. sbe@13.38.0.1) et SBE_SSH_KEY (clé .pem) du .env.
#     bash deploy/fetch-ca.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
env_value() { [[ -f "$ENV_FILE" ]] && grep -E "^[[:space:]]*$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//'; }
TARGET="${SBE_SSH_TARGET:-$(env_value SBE_SSH_TARGET || true)}"
KEY="${SBE_SSH_KEY:-$(env_value SBE_SSH_KEY || true)}"
[[ -n "$TARGET" ]] || { echo "SBE_SSH_TARGET vide dans .env (ex. sbe@13.38.0.1)" >&2; exit 1; }
OUT="$ROOT/infra/mqtt/certs-serveur"
mkdir -p "$OUT"
ssh_opts=()
[[ -n "$KEY" ]] && ssh_opts=(-i "$KEY")
scp "${ssh_opts[@]}" "$TARGET:/opt/sbe/smart-building-emergency/infra/mqtt/certs/ca.pem" "$OUT/ca.pem"
echo "Copié : $OUT/ca.pem"
if command -v openssl >/dev/null; then
  echo "Empreinte (à comparer avec celle affichée sur le serveur) :"
  openssl x509 -in "$OUT/ca.pem" -noout -fingerprint -sha256
fi
