#!/usr/bin/env bash
# Hook « deploy » de certbot (étape 6) : recharge Nginx après chaque renouvellement réussi d'un
# certificat Let's Encrypt, pour que Nginx serve le nouveau fullchain.pem sans redémarrage.
# Sans lui, un certificat obtenu avec « certbot certonly » est bien renouvelé sur le disque, mais
# Nginx continue de servir l'ancien jusqu'à son expiration (90 jours).
#
# Installé par bootstrap.sh dans /etc/letsencrypt/renewal-hooks/deploy/sbe-reload-nginx.sh ;
# certbot l'appelle seul. Essai : sudo bash /etc/letsencrypt/renewal-hooks/deploy/sbe-reload-nginx.sh
set -euo pipefail
if systemctl is-active --quiet nginx; then
  nginx -t -q
  systemctl reload nginx
  echo "Nginx rechargé après le renouvellement de : ${RENEWED_DOMAINS:-certificat}"
fi
