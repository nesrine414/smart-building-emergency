#!/usr/bin/env bash
# Télécharge et installe l'extension HiveMQ File RBAC (authentification + droits par topic).
# HiveMQ Community Edition n'a pas de mot de passe intégré : sans cette extension, le broker
# accepte tout le monde (extension "allow-all") ou personne.
#
# Résultat : infra/mqtt/extensions/hivemq-file-rbac-extension/ (monté dans le conteneur HiveMQ).
# Utilisation, depuis la racine du dépôt :   bash infra/mqtt/install-rbac.sh
set -euo pipefail

VERSION="4.6.19"
# Empreinte SHA-256 de l'archive officielle 4.6.19 (vérifiée au téléchargement)
SHA256="088ef038b0550099cde4d0ac829595c1d53ed0f68635e673b45750ec7036cb4f"
URL="https://github.com/hivemq/hivemq-file-rbac-extension/releases/download/${VERSION}/hivemq-file-rbac-extension-${VERSION}.zip"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$HERE/extensions"
TARGET="$DEST/hivemq-file-rbac-extension"

if [[ -f "$TARGET/hivemq-file-rbac-extension-${VERSION}.jar" ]]; then
  echo "Extension File RBAC ${VERSION} déjà installée dans $TARGET"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "Téléchargement de $URL"
curl -fL --retry 3 -o "$TMP/rbac.zip" "$URL"

ACTUAL="$(sha256sum "$TMP/rbac.zip" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$TMP/rbac.zip" | cut -d' ' -f1)"
if [[ "$ACTUAL" != "$SHA256" ]]; then
  echo "Empreinte SHA-256 inattendue ($ACTUAL) : archive refusée." >&2
  exit 1
fi

mkdir -p "$DEST"
rm -rf "$TARGET"
unzip -q "$TMP/rbac.zip" -d "$DEST"
# Les fichiers d'exemple de l'archive (user1/pass1...) sont remplacés par les nôtres au montage
# Docker ; on les supprime pour qu'ils ne servent jamais par erreur.
rm -f "$TARGET/conf/credentials.xml"
chmod -R a+rX "$TARGET"
echo "Extension installée : $TARGET"
