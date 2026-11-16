#!/usr/bin/env bash
# Prépare un serveur Ubuntu 24.04 neuf pour Smart Building Emergency (étape 4, personne 1).
# À lancer UNE fois, en root, juste après la création de l'instance :
#
#     scp -i cle.pem deploy/server/bootstrap.sh ubuntu@<IP>:/tmp/
#     ssh -i cle.pem ubuntu@<IP> "sudo bash /tmp/bootstrap.sh"
#
# Le script peut être relancé sans danger (chaque étape vérifie si elle est déjà faite).
# Il installe : Docker Engine + Compose, JDK 21 (WildFly et keytool), Python 3 + venv, Git,
# Nginx, certbot ; crée l'utilisateur sbe (même clé SSH que ubuntu), /opt/sbe, 4 Go de swap ;
# ferme la connexion SSH par mot de passe ; active les mises à jour de sécurité automatiques ;
# configure le pare-feu ufw (22, 80, 443, 8883).
#
# Le pare-feu principal reste le groupe de sécurité AWS : ufw est une deuxième barrière, et ne
# protège pas les ports publiés par Docker (d'où docker-compose.prod.yml).
set -euo pipefail

SBE_USER="sbe"
SBE_HOME_DIR="/opt/sbe"
SWAP_SIZE_GB=4

log() { printf '\n==> %s\n' "$*"; }

if [[ $EUID -ne 0 ]]; then
  echo "À lancer en root : sudo bash $0" >&2
  exit 1
fi
# shellcheck disable=SC1091
. /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
  echo "Ce script est prévu pour Ubuntu (trouvé : ${PRETTY_NAME:-inconnu})." >&2
  exit 1
fi
if [[ "${VERSION_ID:-}" != "24.04" ]]; then
  echo "Attention : prévu pour Ubuntu 24.04, trouvé ${VERSION_ID:-?}. On continue." >&2
fi
export DEBIAN_FRONTEND=noninteractive

log "1/8 Paquets de base"
apt-get update -q
apt-get install -y -q ca-certificates curl gnupg git unzip jq \
  openjdk-21-jdk-headless python3 python3-venv python3-pip \
  nginx certbot python3-certbot-nginx ufw unattended-upgrades

log "2/8 Docker Engine et Compose (dépôt officiel de Docker)"
if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker
docker compose version

log "3/8 Utilisateur ${SBE_USER} et dossier ${SBE_HOME_DIR}"
if ! id "${SBE_USER}" >/dev/null 2>&1; then
  adduser --disabled-password --gecos "Smart Building Emergency" "${SBE_USER}"
fi
usermod -aG docker "${SBE_USER}"
install -d -o "${SBE_USER}" -g "${SBE_USER}" -m 0750 "${SBE_HOME_DIR}"
# Même clé SSH que l'utilisateur par défaut de l'image (ubuntu)
SBE_SSH_DIR="$(getent passwd "${SBE_USER}" | cut -d: -f6)/.ssh"
if [[ -f /home/ubuntu/.ssh/authorized_keys && ! -f "${SBE_SSH_DIR}/authorized_keys" ]]; then
  install -d -o "${SBE_USER}" -g "${SBE_USER}" -m 0700 "${SBE_SSH_DIR}"
  install -o "${SBE_USER}" -g "${SBE_USER}" -m 0600 /home/ubuntu/.ssh/authorized_keys "${SBE_SSH_DIR}/authorized_keys"
fi
# Python du serveur pour le plan B de la démo (simulateur lancé par SSH)
if [[ ! -x "${SBE_HOME_DIR}/venv/bin/python" ]]; then
  sudo -u "${SBE_USER}" python3 -m venv "${SBE_HOME_DIR}/venv"
fi
sudo -u "${SBE_USER}" "${SBE_HOME_DIR}/venv/bin/pip" install -q "paho-mqtt>=2.0,<3"

log "4/8 Mémoire d'échange (${SWAP_SIZE_GB} Go)"
if ! swapon --show | grep -q '/swapfile'; then
  if [[ ! -f /swapfile ]]; then
    fallocate -l "${SWAP_SIZE_GB}G" /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
  fi
  swapon /swapfile
fi
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
# Le noyau n'utilise le swap qu'en dernier recours
echo 'vm.swappiness=10' > /etc/sysctl.d/99-sbe-swap.conf
sysctl -q -p /etc/sysctl.d/99-sbe-swap.conf

log "5/8 SSH : clé seulement, pas de root"
cat > /etc/ssh/sshd_config.d/99-sbe.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
sshd -t
systemctl reload ssh

log "6/8 Mises à jour de sécurité automatiques"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

log "7/8 Pare-feu ufw (deuxième barrière derrière le groupe de sécurité AWS)"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw allow 8883/tcp >/dev/null
ufw --force enable >/dev/null
ufw status verbose

log "8/8 Nginx démarré (sa configuration est la tâche de la personne 3)"
systemctl enable --now nginx

cat <<EOF

Serveur prêt.
  Utilisateur  : ${SBE_USER} (UID $(id -u "${SBE_USER}")), groupe docker
  Dossier      : ${SBE_HOME_DIR}
  Connexion    : ssh -i cle.pem ${SBE_USER}@<IP>
  Java         : $(java -version 2>&1 | head -n 1)
  Docker       : $(docker --version)

Étape suivante (en tant que ${SBE_USER}) : deploy/server/deploy-broker-ml.sh (voir deploy/README.md).
EOF
