#!/usr/bin/env bash
# Installe WildFly comme service sur le serveur (étape 6, socle de la personne 1, pour la personne 2).
#
#     sudo bash deploy/server/install-wildfly.sh                      # version par défaut
#     sudo WILDFLY_VERSION=41.0.1.Final bash deploy/server/install-wildfly.sh
#     sudo WILDFLY_TARBALL=/tmp/wildfly-42.0.0.Final.tar.gz bash deploy/server/install-wildfly.sh
#
# Prendre la MÊME version que sur le PC de la personne 2 (celle avec laquelle api.war et iam.war
# ont été testés). Relançable : ce qui est déjà en place n'est pas refait.
#
# Résultat :
#   /opt/wildfly -> /opt/wildfly-<version>   (lien : changer de version = changer le lien)
#   utilisateur système wildfly, membre de rien ; le service lui ajoute le groupe sbe
#   standalone/deployments en wildfly:sbe 2775 : le runner (sbe) y dépose les WAR (deploy-app.sh)
#   /opt/sbe/secrets (root:wildfly 0750) pour la clé OIDC, hors du dépôt
#   /opt/sbe/wildfly-app.env (600) : modèle des variables de l'IAM, à remplir par la personne 2
#   service wildfly.service activé, à l'écoute sur 127.0.0.1:8080 (et 9990 pour jboss-cli)
# Ensuite (personne 2) : scripts jboss-cli de deploy/wildfly-config avec les *-prod.properties.
set -euo pipefail

WILDFLY_VERSION="${WILDFLY_VERSION:-42.0.0.Final}"
WILDFLY_TARBALL="${WILDFLY_TARBALL:-}"
WILDFLY_USER="${WILDFLY_USER:-wildfly}"
SBE_USER="${SBE_USER:-sbe}"
PREFIX="${PREFIX:-/opt}"
SBE_ROOT="${SBE_ROOT:-/opt/sbe}"
UNIT_DIR="${UNIT_DIR:-/etc/systemd/system}"
SYSTEMCTL="${SYSTEMCTL:-systemctl}"
HTTP_CHECK="${HTTP_CHECK:-http://127.0.0.1:8080/}"
START_TIMEOUT="${START_TIMEOUT:-120}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOME_DIR="$PREFIX/wildfly-$WILDFLY_VERSION"
LINK="$PREFIX/wildfly"

log() { printf '\n==> %s\n' "$*"; }
die() { echo "ERREUR : $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "à lancer avec sudo"
id "$SBE_USER" >/dev/null 2>&1 || die "utilisateur $SBE_USER absent : lancer d'abord bootstrap.sh"
command -v java >/dev/null 2>&1 || die "Java absent : lancer d'abord bootstrap.sh (openjdk-21)"
[[ -f "$HERE/wildfly.service" ]] || die "wildfly.service introuvable à côté de ce script"

log "1/6 Utilisateur $WILDFLY_USER"
if id "$WILDFLY_USER" >/dev/null 2>&1; then
  echo "déjà présent"
else
  useradd --system --home-dir "$LINK" --no-create-home --shell /usr/sbin/nologin "$WILDFLY_USER"
  echo "créé (sans shell, sans mot de passe)"
fi

log "2/6 WildFly $WILDFLY_VERSION dans $HOME_DIR"
if [[ -x "$HOME_DIR/bin/standalone.sh" ]]; then
  echo "déjà installé"
else
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  if [[ -n "$WILDFLY_TARBALL" ]]; then
    [[ -f "$WILDFLY_TARBALL" ]] || die "archive introuvable : $WILDFLY_TARBALL"
    cp "$WILDFLY_TARBALL" "$tmp/wildfly.tar.gz"
  else
    url="https://github.com/wildfly/wildfly/releases/download/$WILDFLY_VERSION/wildfly-$WILDFLY_VERSION.tar.gz"
    echo "Téléchargement : $url"
    curl -fsSL -o "$tmp/wildfly.tar.gz" "$url" || die "téléchargement impossible (version $WILDFLY_VERSION ?)"
    if curl -fsSL -o "$tmp/sum" "$url.sha1" 2>/dev/null; then
      expected="$(awk '{ print $1 }' "$tmp/sum")"
      actual="$(sha1sum "$tmp/wildfly.tar.gz" | cut -d' ' -f1)"
      [[ "$expected" == "$actual" ]] || die "empreinte SHA-1 différente de celle publiée : archive rejetée"
      echo "Empreinte publiée vérifiée."
    else
      echo "ATTENTION : pas d'empreinte publiée trouvée. SHA-256 : $(sha256sum "$tmp/wildfly.tar.gz" | cut -d' ' -f1)"
    fi
  fi
  mkdir -p "$tmp/x"
  tar -xzf "$tmp/wildfly.tar.gz" -C "$tmp/x"
  top="$(find "$tmp/x" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  [[ -x "$top/bin/standalone.sh" ]] || die "archive inattendue : pas de bin/standalone.sh"
  mv "$top" "$HOME_DIR"
  echo "installé"
fi
ln -sfn "$HOME_DIR" "$LINK"
chown -R "$WILDFLY_USER:$WILDFLY_USER" "$HOME_DIR"
chmod -R o-rwx "$HOME_DIR/standalone"

log "3/6 Dossier du scanner partagé avec $SBE_USER (pipeline)"
dep="$HOME_DIR/standalone/deployments"
install -d "$dep"
chown "$WILDFLY_USER:$SBE_USER" "$dep"
chmod 2775 "$dep"
# sbe doit pouvoir atteindre ce dossier : on ouvre la traversée (x) des dossiers parents au groupe sbe
chgrp "$SBE_USER" "$HOME_DIR/standalone"
chmod g+x "$HOME_DIR/standalone"
ls -ld "$dep"

log "4/6 Secrets et variables de l'application"
install -d -o root -g "$WILDFLY_USER" -m 0750 "$SBE_ROOT/secrets"
app_env="$SBE_ROOT/wildfly-app.env"
if [[ -f "$app_env" ]]; then
  echo "$app_env existe : conservé"
else
  umask 077
  cat >"$app_env" <<'APPENV'
# Variables de l'IAM et de l'API pour WildFly (personne 2). Droits 600, lu par systemd.
# Après une modification : sudo systemctl restart wildfly
PUBLIC_DOMAIN=sbe-team.me
CORS_ORIGINS=https://www.sbe-team.me,https://admin.sbe-team.me
# OIDC_KEYSTORE_PATH=/opt/sbe/secrets/oidc-keystore.p12
# OIDC_KEYSTORE_PASSWORD=
# OIDC_JWKS_URL=https://iam.sbe-team.me/iam/api/oauth2/jwks
APPENV
  umask 022
  echo "modèle écrit : $app_env (à compléter par la personne 2)"
fi

log "5/6 Service systemd"
install -m 0644 "$HERE/wildfly.service" "$UNIT_DIR/wildfly.service"
"$SYSTEMCTL" daemon-reload
"$SYSTEMCTL" enable wildfly >/dev/null 2>&1 || "$SYSTEMCTL" enable wildfly
"$SYSTEMCTL" restart wildfly

log "6/6 Attente de WildFly sur $HTTP_CHECK"
deadline=$((SECONDS + START_TIMEOUT))
until curl -s -o /dev/null --noproxy '*' -m 5 "$HTTP_CHECK"; do
  if ((SECONDS >= deadline)); then
    die "WildFly ne répond pas après ${START_TIMEOUT} s : sudo journalctl -u wildfly -n 50"
  fi
  sleep 3
done
echo "WildFly répond."

cat <<EOF

WildFly $WILDFLY_VERSION en service (utilisateur $WILDFLY_USER, 127.0.0.1:8080).
  État et journaux : sudo systemctl status wildfly ; sudo journalctl -u wildfly -f
  Déploiements     : $dep ($WILDFLY_USER:$SBE_USER, 2775), alimenté par le pipeline
  À faire (personne 2) :
    1. compléter $app_env, puis : sudo systemctl restart wildfly
    2. nouvelle clé OIDC dans $SBE_ROOT/secrets (root:$WILDFLY_USER, 0640)
    3. $LINK/bin/jboss-cli.sh -c avec deploy/wildfly-config/*.cli et les *-prod.properties
  Puis le pipeline : WILDFLY_HOME=$LINK (valeur par défaut de deploy-app.sh).
EOF
