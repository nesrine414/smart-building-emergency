#!/usr/bin/env bash
# Donne l'accès SSH du serveur à une personne de l'équipe, avec SA clé (étape 6, personne 1).
# Chacune a sa propre clé : on ne partage pas sbe-cle.pem, et on peut retirer un accès sans
# changer celui des autres.
#
# Sur son PC, la personne crée sa clé (une fois) et envoie la ligne .pub (publique, sans risque) :
#     ssh-keygen -t ed25519 -C "prenom@sbe" -f $HOME\.ssh\sbe_ed25519
#     type $HOME\.ssh\sbe_ed25519.pub
# Sur le serveur, quelqu'un qui a déjà accès lance :
#     sudo bash deploy/server/add-ssh-key.sh "ssh-ed25519 AAAA... prenom@sbe"
#     sudo bash deploy/server/add-ssh-key.sh --list
#     sudo bash deploy/server/add-ssh-key.sh --remove prenom@sbe
# La clé est ajoutée aux comptes ubuntu et sbe. Relançable : une clé déjà présente n'est pas dupliquée.
# Le port 22 reste limité aux IP de l'équipe dans le groupe de sécurité AWS (sbe-web).
set -euo pipefail

read -r -a USERS <<<"${SSH_USERS:-ubuntu sbe}"

die() { echo "ERREUR : $*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die "à lancer avec sudo"
[[ $# -ge 1 ]] || die "usage : sudo bash $0 \"ssh-ed25519 AAAA... prenom@sbe\" | --list | --remove <commentaire>"

auth_file() { printf '%s/.ssh/authorized_keys' "$(getent passwd "$1" | cut -d: -f6)"; }

ensure_file() {
  local user="$1" file
  file="$(auth_file "$user")"
  install -d -m 0700 -o "$user" -g "$user" "$(dirname "$file")"
  [[ -f "$file" ]] || install -m 0600 -o "$user" -g "$user" /dev/null "$file"
  printf '%s' "$file"
}

present_users() {
  local u
  for u in "${USERS[@]}"; do
    if id "$u" >/dev/null 2>&1; then echo "$u"; else echo "ATTENTION : utilisateur $u absent, ignoré" >&2; fi
  done
}

case "$1" in
  --list)
    for user in $(present_users); do
      echo "== $user"
      file="$(auth_file "$user")"
      [[ -f "$file" ]] && awk 'NF { print "  " $1 "  " $NF }' "$file"
    done
    ;;
  --remove)
    comment="${2:?--remove demande le commentaire de la clé (ex. prenom@sbe)}"
    for user in $(present_users); do
      file="$(ensure_file "$user")"
      before="$(wc -l <"$file")"
      tmp="$(mktemp)"
      awk -v c="$comment" '$NF != c' "$file" >"$tmp"
      if [[ ! -s "$tmp" ]]; then rm -f "$tmp"; die "$user n'aurait plus aucune clé : refusé (on se fermerait la porte)"; fi
      install -m 0600 -o "$user" -g "$user" "$tmp" "$file"
      rm -f "$tmp"
      echo "$user : $((before - $(wc -l <"$file"))) clé(s) retirée(s)"
    done
    ;;
  *)
    key="$1"
    [[ "$key" =~ ^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521))\ [A-Za-z0-9+/=]{40,}(\ .+)?$ ]] \
      || die "ce n'est pas une clé publique SSH (attendu : ssh-ed25519 AAAA... commentaire). Ne jamais envoyer la clé PRIVÉE."
    [[ "$(awk '{ print NF }' <<<"$key")" -ge 3 ]] \
      || die "ajoute un commentaire à la fin de la clé (ex. prenom@sbe) : il sert à la retirer plus tard"
    tmp="$(mktemp)"
    printf '%s\n' "$key" >"$tmp"
    ssh-keygen -l -f "$tmp" >/dev/null 2>&1 || { rm -f "$tmp"; die "clé illisible par ssh-keygen"; }
    fp="$(ssh-keygen -l -f "$tmp")"
    rm -f "$tmp"
    body="$(awk '{ print $2 }' <<<"$key")"
    for user in $(present_users); do
      file="$(ensure_file "$user")"
      if grep -qF "$body" "$file"; then
        echo "$user : clé déjà présente"
      else
        printf '%s\n' "$key" >>"$file"
        echo "$user : clé ajoutée"
      fi
    done
    echo "Empreinte : $fp"
    echo "À tester depuis le PC concerné : ssh -i <clé privée> ubuntu@<IP> (puis sbe@<IP>)"
    ;;
esac
