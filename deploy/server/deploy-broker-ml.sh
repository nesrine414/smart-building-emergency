#!/usr/bin/env bash
# Met en ligne la partie de la personne 1 sur le serveur (étape 4) : broker HiveMQ en TLS seul
# sur mqtt.<domaine>:8883 avec les comptes RBAC, MongoDB local protégé, et le service sbe-ml.
# À lancer en tant que sbe, depuis le dépôt cloné dans /opt/sbe :
#
#     cd /opt/sbe/smart-building-emergency
#     bash deploy/server/deploy-broker-ml.sh                 # première fois, ou mise à jour
#     bash deploy/server/deploy-broker-ml.sh --force-certs   # régénère les certificats du broker
#
# Peut être relancé sans danger : chaque étape vérifie ce qui est déjà fait.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ENV_FILE="$ROOT/.env"
CERTS="$ROOT/infra/mqtt/certs"
FORCE_CERTS=""
[[ "${1:-}" == "--force-certs" ]] && FORCE_CERTS="--force"

log() { printf '\n==> %s\n' "$*"; }
fail() { echo "ERREUR : $*" >&2; exit 1; }
env_value() { grep -E "^[[:space:]]*$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//'; }
compose() { bash "$ROOT/deploy/compose.sh" "$@"; }

log "1/8 Vérifications"
[[ $EUID -ne 0 ]] || fail "à lancer en tant que sbe, pas en root"
docker info >/dev/null 2>&1 || fail "Docker inaccessible : l'utilisateur doit être dans le groupe docker (se reconnecter après bootstrap.sh)"
# Contrat 6 : le fichier de secrets du serveur est /opt/sbe/.env ; le dépôt y pointe par un lien.
SERVER_ENV="/opt/sbe/.env"
if [[ ! -e "$ENV_FILE" && -f "$SERVER_ENV" ]]; then
  ln -s "$SERVER_ENV" "$ENV_FILE"
  echo "Lien créé : $ENV_FILE -> $SERVER_ENV"
fi
[[ -f "$ENV_FILE" ]] || fail "$SERVER_ENV absent : copie le .env de l'équipe avec scp (jamais par Git)"
chmod 600 "$(readlink -f "$ENV_FILE")"
missing=()
for name in MQTT_BACKEND_PASSWORD MQTT_DEVICE_PASSWORD MQTT_ML_PASSWORD MQTT_KEYSTORE_PASSWORD \
            MQTT_TRUSTSTORE_PASSWORD MQTT_BROKER_PUBLIC_HOST MONGO_ROOT_USER MONGO_ROOT_PASSWORD; do
  [[ -n "$(env_value "$name")" ]] || missing+=("$name")
done
(( ${#missing[@]} == 0 )) || fail "variables vides dans .env : ${missing[*]} (python3 infra/mqtt/gen_credentials.py --init-env)"
PUBLIC_HOST="$(env_value MQTT_BROKER_PUBLIC_HOST)"
echo "Broker public : $PUBLIC_HOST"

log "2/8 Extension HiveMQ File RBAC"
bash infra/mqtt/install-rbac.sh

log "3/8 Certificats du broker"
if [[ -z "$FORCE_CERTS" && -f "$CERTS/broker.pem" ]] && openssl x509 -in "$CERTS/broker.pem" -noout -ext subjectAltName 2>/dev/null \
     | tr ',' '\n' | sed 's/^ *//' | grep -qxF "DNS:${PUBLIC_HOST}"; then
  echo "Certificats déjà valables pour $PUBLIC_HOST (--force-certs pour les refaire)"
else
  bash infra/mqtt/gen-certs.sh --force
fi

log "4/8 Comptes MQTT (credentials.xml)"
python3 infra/mqtt/gen_credentials.py

log "5/8 Image sbe-ml et modèle"
compose build sbe-ml
current_model_ok() {
  python3 - "$ROOT/iot/mlops/models" <<'PY'
import json, sys, pathlib
models = pathlib.Path(sys.argv[1])
try:
    version = json.loads((models / "registry.json").read_text(encoding="utf-8"))["current"]
except (OSError, ValueError, KeyError):
    sys.exit(1)
sys.exit(0 if version and (models / f"model-v{version}.joblib").exists() else 1)
PY
}
if current_model_ok; then
  echo "Modèle courant présent : pas de nouvel entraînement"
else
  [[ -f iot/mlops/data/smoke_detection_iot.csv ]] || fail "pas de modèle et pas de dataset : copie smoke_detection_iot.csv dans iot/mlops/data/ (scp), puis relance"
  echo "Entraînement dans l'image sbe-ml (même scikit-learn qu'en service)..."
  compose run --rm --no-deps sbe-ml python train.py --notes "serveur $(date +%F)"
  current_model_ok || fail "l'entraînement n'a pas promu de modèle (voir les messages ci-dessus)"
fi

log "6/8 Variables MQTT de WildFly pour la personne 2 (/opt/sbe/wildfly-mqtt.env)"
# À ajouter au service systemd de WildFly : EnvironmentFile=/opt/sbe/wildfly-mqtt.env
umask 077
cat > /opt/sbe/wildfly-mqtt.env <<WFENV
# Généré par deploy/server/deploy-broker-ml.sh le $(date '+%F %T'). Ne pas modifier à la main.
MQTT_BROKER_URL=ssl://localhost:8883
MQTT_USERNAME=sbe-backend
MQTT_PASSWORD=$(env_value MQTT_BACKEND_PASSWORD)
MQTT_TRUSTSTORE=$CERTS/truststore.jks
MQTT_TRUSTSTORE_PASSWORD=$(env_value MQTT_TRUSTSTORE_PASSWORD)
WFENV
umask 022
echo "Écrit : /opt/sbe/wildfly-mqtt.env (droits 600)"

log "7/8 Démarrage de MongoDB, HiveMQ et sbe-ml"
compose up -d mongodb hivemq sbe-ml
echo "Attente de l'état healthy de sbe-ml (2 minutes au plus)..."
state=""
for _ in $(seq 1 24); do
  state="$(docker inspect -f '{{.State.Health.Status}}' smart-building-sbe-ml 2>/dev/null || echo absent)"
  [[ "$state" == "healthy" ]] && break
  sleep 5
done
compose ps
[[ "$state" == "healthy" ]] || fail "sbe-ml n'est pas healthy ($state) : bash deploy/compose.sh logs sbe-ml"

log "8/8 Contrôle de la sécurité MQTT sur le serveur"
PY="/opt/sbe/venv/bin/python"
[[ -x "$PY" ]] || PY="python3"
"$PY" infra/mqtt/check_mqtt_security.py --host localhost --cafile "$CERTS/ca.pem"

FINGERPRINT="$(openssl x509 -in "$CERTS/ca.pem" -noout -fingerprint -sha256 | cut -d= -f2)"
cat <<MSG

Partie personne 1 en ligne.
  Broker  : mqtt://${PUBLIC_HOST}:8883 (TLS seul, comptes RBAC)
  CA      : $CERTS/ca.pem   -> à récupérer sur vos PC avec deploy/fetch-ca.ps1 (ou .sh)
  Empreinte SHA-256 de la CA (fetch-ca doit afficher la même) :
            $FINGERPRINT
  Journal : bash deploy/compose.sh logs -f sbe-ml
  WildFly : EnvironmentFile=/opt/sbe/wildfly-mqtt.env (personne 2)
  MongoDB : mongodb://$(env_value MONGO_ROOT_USER):<MONGO_ROOT_PASSWORD>@localhost:27017/?authSource=admin
MSG
