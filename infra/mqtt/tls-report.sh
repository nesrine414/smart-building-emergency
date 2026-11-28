#!/usr/bin/env bash
# Rapport TLS du broker en ligne avec testssl.sh (étape 4) : protocoles, suites de chiffrement,
# certificat, failles connues. Rapport HTML + JSON dans infra/mqtt/reports/.
#
#     bash infra/mqtt/tls-report.sh                      # MQTT_BROKER_PUBLIC_HOST:8883 (du .env)
#     bash infra/mqtt/tls-report.sh mqtt.exemple.me:8883
#     bash infra/mqtt/tls-report.sh --ip 13.62.97.65      # DNS pas encore propagé
#
# Toujours viser le NOM du broker (celui du certificat), jamais l'IP seule : sinon testssl signale
# « certificate does not match supplied URI ». --ip garde le nom et force l'IP.
#
# Utilise testssl.sh s'il est installé, sinon son image Docker officielle.
# --add-ca : testssl fait confiance à la CA du projet (sinon « chaîne de confiance invalide »).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
ENV_FILE="$ROOT/.env"
env_value() { [[ -f "$ENV_FILE" ]] && grep -E "^[[:space:]]*$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//'; }

TARGET=""
IP_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ip) IP_ARGS=(--ip "${2:?--ip demande une adresse}"); shift 2 ;;
    *) TARGET="$1"; shift ;;
  esac
done
if [[ "$TARGET" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}(:[0-9]+)?$ ]]; then
  echo "Cible = une IP : le certificat porte le nom du broker. Utilise mqtt.<domaine>:8883 (et --ip ${TARGET%%:*} si le DNS n'est pas prêt)." >&2
  exit 1
fi
if [[ -z "$TARGET" ]]; then
  PUBLIC_HOST="$(env_value MQTT_BROKER_PUBLIC_HOST || true)"
  [[ -n "$PUBLIC_HOST" ]] || { echo "Indique la cible (hôte:port) ou MQTT_BROKER_PUBLIC_HOST dans .env" >&2; exit 1; }
  TARGET="$PUBLIC_HOST:8883"
fi
CA="${CA_FILE:-$HERE/certs-serveur/ca.pem}"
[[ -f "$CA" ]] || CA="$HERE/certs/ca.pem"
[[ -f "$CA" ]] || { echo "Certificat de la CA introuvable (deploy/fetch-ca.sh, ou CA_FILE=...)" >&2; exit 1; }
OUT="$HERE/reports"
mkdir -p "$OUT"
chmod a+w "$OUT"   # l'image testssl écrit avec son propre utilisateur

echo "Rapport TLS de $TARGET (CA : $CA)"
if command -v testssl.sh >/dev/null || command -v testssl >/dev/null; then
  TOOL="$(command -v testssl.sh || command -v testssl)"
  "$TOOL" --add-ca "$CA" "${IP_ARGS[@]}" --htmlfile "$OUT/" --jsonfile "$OUT/" "$TARGET" \
    || echo "testssl a signalé des points à regarder (code $?) : voir le rapport."
else
  command -v docker >/dev/null || { echo "Ni testssl.sh ni Docker : installe l'un des deux" >&2; exit 1; }
  docker run --rm -t -v "$OUT:/data" -v "$(dirname "$CA"):/certs:ro" --workdir /data \
    ghcr.io/testssl/testssl.sh:3.2 --add-ca "/certs/$(basename "$CA")" "${IP_ARGS[@]}" --htmlfile /data/ --jsonfile /data/ "$TARGET" \
    || echo "testssl a signalé des points à regarder (code $?) : voir le rapport."
fi
echo
echo "Rapports dans $OUT :"
# shellcheck disable=SC2012  # noms produits par testssl, sans espace
ls -1t "$OUT" | head -n 2
