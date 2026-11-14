#!/usr/bin/env bash
# Génère les certificats TLS du broker MQTT (étape 3, contrat 5) avec keytool seulement.
#
#   certs/ca.jks          CA locale du projet (clé privée de la CA : à garder pour re-signer)
#   certs/ca.pem          certificat de la CA, pour les clients Python (--cafile) et l'ESP32
#   certs/broker.jks      clé et certificat du broker HiveMQ (signé par la CA), monté dans le conteneur
#   certs/truststore.jks  certificat de la CA, pour le backend Java (MQTT_TRUSTSTORE)
#
# Le certificat du broker est valable pour localhost, 127.0.0.1, hivemq (nom du service Docker),
# mqtt.smartbuilding.local et, si MQTT_BROKER_LAN_IP est défini dans .env, l'IP du PC sur le Wi-Fi.
#
# Utilisation, depuis la racine du dépôt :   bash infra/mqtt/gen-certs.sh [--force]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/certs"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
FORCE="${1:-}"

# Lit une variable : environnement d'abord, puis .env
env_value() {
  local name="$1" value="${!1:-}"
  if [[ -z "$value" && -f "$ENV_FILE" ]]; then
    value="$(grep -E "^[[:space:]]*${name}=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//')"
  fi
  printf '%s' "$value"
}

KEYSTORE_PASSWORD="$(env_value MQTT_KEYSTORE_PASSWORD)"
TRUSTSTORE_PASSWORD="$(env_value MQTT_TRUSTSTORE_PASSWORD)"
LAN_IP="$(env_value MQTT_BROKER_LAN_IP)"

if [[ ${#KEYSTORE_PASSWORD} -lt 6 || ${#TRUSTSTORE_PASSWORD} -lt 6 ]]; then
  echo "MQTT_KEYSTORE_PASSWORD et MQTT_TRUSTSTORE_PASSWORD doivent faire au moins 6 caractères (.env)." >&2
  echo "Astuce : python infra/mqtt/gen_credentials.py --init-env" >&2
  exit 1
fi
command -v keytool >/dev/null || { echo "keytool introuvable : installe un JDK (17 ou plus) et ajoute-le au PATH." >&2; exit 1; }

if [[ -f "$OUT/broker.jks" && "$FORCE" != "--force" ]]; then
  echo "$OUT/broker.jks existe déjà. Relance avec --force pour tout régénérer." >&2
  exit 1
fi
rm -rf "$OUT"
mkdir -p "$OUT"
cd "$OUT"

SAN="dns:localhost,ip:127.0.0.1,dns:hivemq,dns:mqtt.smartbuilding.local"
if [[ -n "$LAN_IP" ]]; then
  SAN="$SAN,ip:$LAN_IP"
fi

echo "1/5 CA locale du projet"
keytool -genkeypair -alias sbe-ca -keyalg RSA -keysize 3072 -validity 3650 \
  -dname "CN=Smart Building Emergency Local CA, O=SupCom, C=TN" \
  -ext bc:c=ca:true,pathlen:0 -ext ku:c=keyCertSign,cRLSign \
  -keystore ca.jks -storetype JKS -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD"
keytool -exportcert -rfc -alias sbe-ca -keystore ca.jks -storepass "$KEYSTORE_PASSWORD" -file ca.pem

echo "2/5 Clé du broker"
keytool -genkeypair -alias broker -keyalg RSA -keysize 2048 -validity 825 \
  -dname "CN=localhost, O=SupCom, C=TN" \
  -keystore broker.jks -storetype JKS -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD"

echo "3/5 Signature du certificat du broker par la CA ($SAN)"
keytool -certreq -alias broker -keystore broker.jks -storepass "$KEYSTORE_PASSWORD" -file broker.csr
keytool -gencert -alias sbe-ca -keystore ca.jks -storepass "$KEYSTORE_PASSWORD" \
  -infile broker.csr -outfile broker.pem -rfc -validity 825 \
  -ext "san=$SAN" -ext ku:c=digitalSignature,keyEncipherment -ext eku=serverAuth

echo "4/5 Chaîne complète dans broker.jks"
keytool -importcert -noprompt -alias sbe-ca -file ca.pem -keystore broker.jks -storepass "$KEYSTORE_PASSWORD"
keytool -importcert -noprompt -alias broker -file broker.pem -keystore broker.jks -storepass "$KEYSTORE_PASSWORD"

echo "5/5 Truststore des clients Java"
keytool -importcert -noprompt -alias sbe-ca -file ca.pem \
  -keystore truststore.jks -storetype JKS -storepass "$TRUSTSTORE_PASSWORD"

rm -f broker.csr
# Le conteneur HiveMQ tourne avec l'utilisateur 10000 : il doit pouvoir lire le keystore.
chmod 644 broker.jks truststore.jks ca.pem broker.pem
chmod 600 ca.jks

echo
echo "Certificats prêts dans $OUT :"
ls -1 "$OUT"
