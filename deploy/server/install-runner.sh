#!/usr/bin/env bash
# Installe le runner GitHub Actions sur le serveur (étape 5, personne 1).
# Le job « deploy » du pipeline tourne ici, en tant que sbe : aucun port à ouvrir, aucun secret
# SSH dans GitHub. Le runner se connecte lui-même à GitHub en HTTPS sortant.
#
#     sudo bash deploy/server/install-runner.sh https://github.com/<compte>/<dépôt>
#
# Le jeton d'inscription est demandé au clavier (il ne reste pas dans l'historique du shell) ;
# il vient de GitHub > dépôt > Settings > Actions > Runners > New self-hosted runner, et n'est
# valable qu'une heure. On peut aussi le passer par la variable RUNNER_TOKEN.
#
# DÉPÔT PRIVÉ OBLIGATOIRE : sur un dépôt public, n'importe qui pourrait, par une pull request
# venant d'un fork, faire exécuter son code sur ce serveur.
#
# Résultat : runner « sbe-server » (étiquette sbe-server) dans /opt/sbe/actions-runner, service
# systemd qui redémarre avec la machine. Relançable : un runner déjà inscrit est seulement
# (re)démarré, sauf avec --replace (nouvelle inscription, nouveau jeton).
set -euo pipefail

SBE_USER="${SBE_USER:-sbe}"
RUNNER_DIR="${RUNNER_DIR:-/opt/sbe/actions-runner}"
RUNNER_NAME="${RUNNER_NAME:-sbe-server}"
RUNNER_LABELS="${RUNNER_LABELS:-sbe-server}"
RUNNER_VERSION="${RUNNER_VERSION:-}"   # vide : dernière version publiée
REPLACE=0
REPO_URL=""

log() { printf '\n==> %s\n' "$*"; }
die() { echo "ERREUR : $*" >&2; exit 1; }

for arg in "$@"; do
  case "$arg" in
    --replace) REPLACE=1 ;;
    -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    https://github.com/*) REPO_URL="${arg%.git}" ;;
    *) die "argument inattendu : $arg (attendu : https://github.com/<compte>/<dépôt> [--replace])" ;;
  esac
done
[[ -n "$REPO_URL" ]] || die "usage : sudo bash $0 https://github.com/<compte>/<dépôt> [--replace]"
[[ $EUID -eq 0 ]] || die "à lancer avec sudo (le service systemd s'installe en root)"
id "$SBE_USER" >/dev/null 2>&1 || die "utilisateur $SBE_USER absent : lancer d'abord bootstrap.sh"
command -v jq >/dev/null 2>&1 || die "jq manquant (installé par bootstrap.sh)"

as_sbe() { sudo -u "$SBE_USER" -H "$@"; }

# Un dépôt public répond sans authentification à l'API GitHub ; un dépôt privé répond 404.
slug="${REPO_URL#https://github.com/}"
visibility="$(curl -s -o /dev/null -w '%{http_code}' "https://api.github.com/repos/${slug}" || true)"
case "$visibility" in
  200)
    [[ "${ALLOW_PUBLIC_REPO:-0}" == "1" ]] \
      || die "le dépôt ${slug} est PUBLIC : un runner sur ce serveur exécuterait le code de n'importe quelle pull request de fork. Le passer en privé (Settings > General > Danger Zone > Change visibility)."
    ;;
  404) echo "Dépôt ${slug} : privé (invisible sans compte), d'accord." ;;
  *) echo "ATTENTION : visibilité de ${slug} non vérifiée (réponse ${visibility:-aucune}). Le dépôt DOIT être privé." ;;
esac

case "$(uname -m)" in
  x86_64) ARCH="x64" ;;
  aarch64) ARCH="arm64" ;;
  *) die "architecture non prévue : $(uname -m)" ;;
esac

install -d -o "$SBE_USER" -g "$SBE_USER" -m 0750 "$RUNNER_DIR"

if [[ ! -x "$RUNNER_DIR/config.sh" ]]; then
  log "1/4 Téléchargement du runner"
  release_json="$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest || true)"
  if [[ -z "$RUNNER_VERSION" ]]; then
    RUNNER_VERSION="$(jq -r '.tag_name // empty' <<<"$release_json" | sed 's/^v//')"
    [[ -n "$RUNNER_VERSION" ]] || die "version du runner introuvable (API GitHub) : relancer avec RUNNER_VERSION=2.x.y"
  fi
  file="actions-runner-linux-${ARCH}-${RUNNER_VERSION}.tar.gz"
  url="https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${file}"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/$file" "$url"
  # Empreinte publiée dans les notes de version : <!-- BEGIN SHA linux-x64 -->…<!-- END SHA linux-x64 -->
  expected=""
  if [[ "$(jq -r '.tag_name // empty' <<<"$release_json")" == "v${RUNNER_VERSION}" ]]; then
    expected="$(jq -r '.body' <<<"$release_json" \
      | sed -n "s/.*<!-- BEGIN SHA linux-${ARCH} -->\([0-9a-f]\{64\}\)<!-- END SHA linux-${ARCH} -->.*/\1/p" | head -n1)"
  fi
  actual="$(sha256sum "$tmp/$file" | cut -d' ' -f1)"
  if [[ -n "$expected" ]]; then
    [[ "$actual" == "$expected" ]] || die "empreinte SHA-256 différente de celle publiée par GitHub : archive rejetée"
    echo "Empreinte vérifiée : $actual"
  else
    echo "ATTENTION : empreinte publiée non trouvée (version $RUNNER_VERSION). SHA-256 : $actual"
    echo "            À comparer avec https://github.com/actions/runner/releases/tag/v${RUNNER_VERSION}"
  fi
  tar -xzf "$tmp/$file" -C "$RUNNER_DIR"
  chown -R "$SBE_USER:$SBE_USER" "$RUNNER_DIR"
else
  log "1/4 Runner déjà présent dans $RUNNER_DIR"
fi

log "2/4 Bibliothèques système du runner"
"$RUNNER_DIR/bin/installdependencies.sh" >/dev/null

if [[ -f "$RUNNER_DIR/.runner" && "$REPLACE" -eq 0 ]]; then
  log "3/4 Déjà inscrit ($(jq -r '.agentName // "?"' "$RUNNER_DIR/.runner" 2>/dev/null || echo '?')) : inscription conservée (--replace pour refaire)"
else
  log "3/4 Inscription auprès de $REPO_URL"
  if [[ -z "${RUNNER_TOKEN:-}" ]]; then
    read -r -s -p "Jeton d'inscription (Settings > Actions > Runners > New self-hosted runner) : " RUNNER_TOKEN
    echo
  fi
  [[ -n "${RUNNER_TOKEN:-}" ]] || die "jeton vide"
  if [[ -f "$RUNNER_DIR/.runner" ]]; then
    # config.sh refuse un dossier déjà configuré : on retire le service et la configuration locale,
    # puis --replace remplace l'inscription du même nom côté GitHub.
    if [[ -f "$RUNNER_DIR/.service" ]]; then
      (cd "$RUNNER_DIR" && ./svc.sh stop >/dev/null 2>&1; ./svc.sh uninstall >/dev/null 2>&1) || true
    fi
    rm -f "$RUNNER_DIR/.runner" "$RUNNER_DIR/.credentials" "$RUNNER_DIR/.credentials_rsaparams" "$RUNNER_DIR/.service"
  fi
  (cd "$RUNNER_DIR" && as_sbe ./config.sh --unattended --replace \
    --url "$REPO_URL" --token "$RUNNER_TOKEN" \
    --name "$RUNNER_NAME" --labels "$RUNNER_LABELS" --work _work)
fi

log "4/4 Service systemd"
cd "$RUNNER_DIR"
if [[ ! -f .service ]]; then
  ./svc.sh install "$SBE_USER"
fi
./svc.sh start >/dev/null 2>&1 || true
./svc.sh status | sed -n '1,6p' || true

cat <<EOF

Runner installé.
  Nom / étiquette : ${RUNNER_NAME} / ${RUNNER_LABELS}, utilisateur ${SBE_USER}
  Dossier         : ${RUNNER_DIR}
  Vérifier        : GitHub > Settings > Actions > Runners : « ${RUNNER_NAME} » doit être Idle.
  Après un redémarrage du serveur, il revient seul (service $(cat .service 2>/dev/null || echo 'actions.runner.*')).
EOF
