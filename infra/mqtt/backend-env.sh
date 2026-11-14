#!/usr/bin/env bash
# Prépare les variables d'environnement MQTT du backend (WildFly) dans le terminal en cours.
# À lancer avec "source", dans le terminal qui démarrera WildFly :
#
#     source infra/mqtt/backend-env.sh
#     "$WILDFLY_HOME/bin/standalone.sh"
#
# Les valeurs viennent du fichier .env à la racine du dépôt. Rien n'est écrit sur le disque.
_sbe_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_sbe_env="$_sbe_here/../../.env"
_sbe_value() { grep -E "^[[:space:]]*$1=" "$_sbe_env" | tail -n 1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//'; }

if [[ ! -f "$_sbe_env" ]]; then
  echo "Fichier .env introuvable : lance d'abord python infra/mqtt/gen_credentials.py --init-env" >&2
elif [[ ! -f "$_sbe_here/certs/truststore.jks" ]]; then
  echo "Truststore introuvable : lance d'abord bash infra/mqtt/gen-certs.sh" >&2
else
  export MQTT_BROKER_URL="ssl://localhost:8883"
  export MQTT_USERNAME="sbe-backend"
  export MQTT_PASSWORD="$(_sbe_value MQTT_BACKEND_PASSWORD)"
  export MQTT_TRUSTSTORE="$_sbe_here/certs/truststore.jks"
  export MQTT_TRUSTSTORE_PASSWORD="$(_sbe_value MQTT_TRUSTSTORE_PASSWORD)"
  _sbe_jwt="$(_sbe_value JWT_SECRET)"
  [[ -n "$_sbe_jwt" ]] && export JWT_SECRET="$_sbe_jwt"   # utilisé par la personne 2
  echo "Variables MQTT prêtes pour WildFly : $MQTT_BROKER_URL, $MQTT_USERNAME, $MQTT_TRUSTSTORE"
fi
unset _sbe_here _sbe_env _sbe_jwt
unset -f _sbe_value
