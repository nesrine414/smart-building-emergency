#!/usr/bin/env bash
# Ajoute une clé publique SSH aux comptes ubuntu et sbe (sans doublon).
# Usage : sudo bash add-ssh-key.sh "ssh-ed25519 AAAA... commentaire"
#         sudo bash add-ssh-key.sh --list
set -euo pipefail

if [ "${1:-}" = "--list" ]; then
    for user in ubuntu sbe; do
        home=$(eval echo "~$user")
        echo "=== $user ==="
        cat "$home/.ssh/authorized_keys" 2>/dev/null || echo "(vide)"
    done
    exit 0
fi

KEY="${1:?Usage: sudo bash add-ssh-key.sh \"ssh-ed25519 AAAA... commentaire\"}"

for user in ubuntu sbe; do
    home=$(eval echo "~$user")
    mkdir -p "$home/.ssh"
    chmod 700 "$home/.ssh"
    touch "$home/.ssh/authorized_keys"
    chmod 600 "$home/.ssh/authorized_keys"
    if grep -qF "$KEY" "$home/.ssh/authorized_keys" 2>/dev/null; then
        echo "[$user] Clé déjà présente, ignorée."
    else
        echo "$KEY" >> "$home/.ssh/authorized_keys"
        chown -R "$user:$user" "$home/.ssh"
        echo "[$user] Clé ajoutée."
    fi
done