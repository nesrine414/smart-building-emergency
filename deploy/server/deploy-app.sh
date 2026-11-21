#!/usr/bin/env bash
# Met en ligne une version construite par le pipeline CI/CD (étape 5, personne 1).
#
# Lancé par le runner GitHub Actions installé sur le serveur (utilisateur sbe), job « deploy » :
#     bash deploy/server/deploy-app.sh --sha <commit> <dossier des artefacts>
# À la main, sur le serveur (en sbe) :
#     bash deploy/server/deploy-app.sh --list         # versions installées
#     bash deploy/server/deploy-app.sh --rollback     # revient à la version réussie précédente
#
# Dossier des artefacts (fabriqué par deploy/ci/build-artifacts.sh) :
#     wars/iam.war  wars/api.war  frontoffice/  backoffice/  BUILD_INFO
#
# Étapes : verrou ; copie dans /opt/sbe/releases/<date>-<sha> ; photo de ce qui est en ligne ;
# dépôt du serveur avancé jusqu'au commit (avance rapide seulement) ; sbe-ml reconstruit si son code
# ou les fichiers compose ont changé ; PWA remplacées d'un bloc ; WAR déployés par le scanner de
# WildFly (attente du marqueur .deployed) ; contrôles HTTP et état healthy de sbe-ml.
# Au moindre échec : retour automatique à la photo, et code de sortie 1 (le pipeline passe au rouge).
#
# Aucun secret ici : PUBLIC_DOMAIN est lu dans /opt/sbe/.env. Réglages facultatifs (chemins,
# SMOKE_URLS, délais) dans /opt/sbe/deploy.env, voir deploy/CI-CD.md.
set -euo pipefail

SBE_ROOT="${SBE_ROOT:-/opt/sbe}"
if [[ -f "${SBE_ROOT}/deploy.env" ]]; then
  # shellcheck disable=SC1091
  . "${SBE_ROOT}/deploy.env"
fi
REPO_DIR="${REPO_DIR:-${SBE_ROOT}/smart-building-emergency}"
ENV_FILE="${ENV_FILE:-${SBE_ROOT}/.env}"
RELEASES_DIR="${RELEASES_DIR:-${SBE_ROOT}/releases}"
WILDFLY_HOME="${WILDFLY_HOME:-/opt/wildfly}"
DEPLOYMENTS_DIR="${DEPLOYMENTS_DIR:-${WILDFLY_HOME}/standalone/deployments}"
WEB_ROOT="${WEB_ROOT:-/var/opt/wildfly}"
WILDFLY_HTTP="${WILDFLY_HTTP:-http://127.0.0.1:8080}"
GIT_REMOTE="${GIT_REMOTE:-origin}"
KEEP_RELEASES="${KEEP_RELEASES:-5}"
DEPLOY_TIMEOUT="${DEPLOY_TIMEOUT:-300}"   # secondes par WAR
SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-120}"     # secondes pour l'ensemble des contrôles HTTP
SBE_ML_TIMEOUT="${SBE_ML_TIMEOUT:-180}"
CHECK_SBE_ML="${CHECK_SBE_ML:-auto}"      # auto : contrôlé si docker est présent ; 1 : obligatoire ; 0 : non
POLL="${POLL:-2}"
SMOKE_URLS="${SMOKE_URLS:-}"              # vide : contrôles par défaut (voir default_smoke)
FRONTS=(frontoffice backoffice)
WAR_ORDER=(iam.war api.war)               # l'IAM d'abord : l'API lit son JWKS

MODE="deploy"
SHA=""
NO_GIT=0
ARTIFACTS=""
PHASE="preparation"
REL=""
ROLLED_FROM=""

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
warn() {
  log "ATTENTION : $*"
  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then echo "::warning::$*"; fi
}
die() {
  log "ERREUR : $*"
  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then echo "::error::$*"; fi
  exit 1
}

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

# ---------------------------------------------------------------- arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --sha) SHA="${2:-}"; shift 2 ;;
    --no-git) NO_GIT=1; shift ;;
    --rollback) MODE="rollback"; shift ;;
    --list) MODE="list"; shift ;;
    -h|--help) usage 0 ;;
    -*) echo "Option inconnue : $1" >&2; usage 2 ;;
    *) ARTIFACTS="$1"; shift ;;
  esac
done

release_dirs() {
  # Dossiers de versions, du plus ancien au plus récent (le nom commence par la date UTC).
  local d
  shopt -s nullglob
  for d in "${RELEASES_DIR}"/2*; do
    [[ -d "$d" ]] && printf '%s\n' "$d"
  done
  shopt -u nullglob
}

current_release() {
  if [[ -f "${RELEASES_DIR}/CURRENT" ]]; then cat "${RELEASES_DIR}/CURRENT"; fi
}

list_releases() {
  local cur d name
  cur="$(current_release)"
  printf '%-28s %-9s %s\n' "VERSION" "COMMIT" "ÉTAT"
  while IFS= read -r d; do
    name="$(basename "$d")"
    printf '%-28s %-9s %s%s\n' "$name" "$(cut -c1-7 "$d/sha" 2>/dev/null || echo '-')" \
      "$(cat "$d/status" 2>/dev/null || echo '?')" "$([[ "$name" == "$cur" ]] && echo '   <- en ligne')"
  done < <(release_dirs)
}

if [[ "$MODE" == "list" ]]; then
  list_releases
  exit 0
fi

if [[ "$MODE" == "rollback" ]]; then
  cur="$(current_release)"
  [[ -n "$cur" ]] || die "aucune version en ligne enregistrée dans ${RELEASES_DIR}/CURRENT"
  # On cherche avant la version en ligne ; si elle est elle-même un retour, avant celle qu'elle a
  # remise en ligne (deux --rollback de suite reculent de deux versions, sans aller-retour).
  limit="$cur"
  if [[ -f "${RELEASES_DIR}/${cur}/rollback-of" ]]; then limit="$(cat "${RELEASES_DIR}/${cur}/rollback-of")"; fi
  cur_sha="$(cat "${RELEASES_DIR}/${cur}/sha" 2>/dev/null || true)"
  prev=""
  while IFS= read -r d; do
    name="$(basename "$d")"
    if [[ "$name" < "$limit" && "$(cat "$d/status" 2>/dev/null)" == "ok" && -d "$d/artifacts" \
          && "$(cat "$d/sha" 2>/dev/null)" != "$cur_sha" ]]; then
      prev="$d"
    fi
  done < <(release_dirs)
  [[ -n "$prev" ]] || die "pas de version réussie plus ancienne que ${cur} (voir --list)"
  ARTIFACTS="${prev}/artifacts"
  SHA="$(cat "${prev}/sha")"
  ROLLED_FROM="$(basename "$prev")"
  log "Retour demandé : ${cur} -> ${ROLLED_FROM} (commit ${SHA:0:7})"
fi

# ---------------------------------------------------------------- contrôles avant toute modification
[[ -n "$ARTIFACTS" ]] || usage 2
[[ -d "$ARTIFACTS" ]] || die "dossier d'artefacts introuvable : $ARTIFACTS"
ARTIFACTS="$(cd "$ARTIFACTS" && pwd)"
for tool in git curl flock; do
  command -v "$tool" >/dev/null 2>&1 || die "commande manquante : $tool"
done
if [[ -z "$SHA" && -f "$ARTIFACTS/BUILD_INFO" ]]; then
  SHA="$(sed -n 's/^sha=//p' "$ARTIFACTS/BUILD_INFO" | head -n1)"
fi
if [[ "$NO_GIT" -eq 0 ]]; then
  [[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || die "--sha <commit complet> obligatoire (ou --no-git)"
  git -C "$REPO_DIR" rev-parse --git-dir >/dev/null 2>&1 || die "pas de dépôt Git dans $REPO_DIR (deploy/README.md, section 4)"
fi

shopt -s nullglob
WARS_IN=("$ARTIFACTS"/wars/*.war)
shopt -u nullglob
# Ordre de déploiement : WAR_ORDER d'abord, puis les autres
WARS=()
for w in "${WAR_ORDER[@]}"; do
  [[ -f "$ARTIFACTS/wars/$w" ]] && WARS+=("$ARTIFACTS/wars/$w")
done
for w in "${WARS_IN[@]}"; do
  [[ " ${WAR_ORDER[*]} " == *" $(basename "$w") "* ]] || WARS+=("$w")
done
FRONTS_IN=()
for f in "${FRONTS[@]}"; do
  if [[ -d "$ARTIFACTS/$f" ]]; then
    [[ -f "$ARTIFACTS/$f/index.html" ]] || die "$f sans index.html : build Angular incomplet"
    FRONTS_IN+=("$f")
  fi
done
[[ ${#WARS[@]} -gt 0 || ${#FRONTS_IN[@]} -gt 0 ]] || die "rien à déployer dans $ARTIFACTS"

if [[ ${#WARS[@]} -gt 0 ]]; then
  [[ -d "$DEPLOYMENTS_DIR" ]] || die "dossier de déploiement WildFly introuvable : $DEPLOYMENTS_DIR (WILDFLY_HOME ?)"
  [[ -w "$DEPLOYMENTS_DIR" ]] || die "$DEPLOYMENTS_DIR non modifiable par $(id -un) (voir deploy/CI-CD.md, « Droits »)"
fi
if [[ ${#FRONTS_IN[@]} -gt 0 ]]; then
  [[ -d "$WEB_ROOT" && -w "$WEB_ROOT" ]] || die "$WEB_ROOT absent ou non modifiable par $(id -un) (bootstrap.sh le crée)"
fi

read_env_var() {
  [[ -f "$ENV_FILE" ]] || return 0
  sed -n "s/^$1=//p" "$ENV_FILE" | tail -n1 | tr -d '\r' | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

default_smoke() {
  local d="$1"
  local n
  for n in "${FRONTS_IN[@]}"; do
    case "$n" in
      frontoffice) echo "${WILDFLY_HTTP}/|www.${d}|<html" ;;
      backoffice) echo "${WILDFLY_HTTP}/|admin.${d}|<html" ;;
    esac
  done
  for n in "${WARS[@]}"; do
    if [[ "$(basename "$n")" == "iam.war" ]]; then
      echo "${WILDFLY_HTTP}/iam/api/oauth2/.well-known/openid-configuration|iam.${d}|\"issuer\""
    fi
  done
}

SMOKE=()
if [[ -n "$SMOKE_URLS" ]]; then
  # Entrées séparées par des espaces ou des retours à la ligne : URL|Host|texte attendu
  mapfile -t SMOKE < <(tr -s ' \t' '\n' <<<"$SMOKE_URLS" | sed '/^$/d')
else
  PUBLIC_DOMAIN="$(read_env_var PUBLIC_DOMAIN)"
  [[ -n "$PUBLIC_DOMAIN" ]] || die "PUBLIC_DOMAIN vide dans $ENV_FILE (ou définir SMOKE_URLS dans ${SBE_ROOT}/deploy.env)"
  mapfile -t SMOKE < <(default_smoke "$PUBLIC_DOMAIN")
fi

# ---------------------------------------------------------------- verrou et dossier de version
mkdir -p "$RELEASES_DIR"
exec 9>"${RELEASES_DIR}/.lock"
flock -w 900 9 || die "un autre déploiement tourne depuis 15 minutes"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="${STAMP}-${SHA:0:7}"
[[ -n "${SHA}" ]] || NAME="${STAMP}-nogit"
REL="${RELEASES_DIR}/${NAME}"
i=2
while [[ -e "$REL" ]]; do REL="${RELEASES_DIR}/${NAME}-$i"; i=$((i + 1)); done
mkdir -p "$REL/state" "$REL/before/wars"
exec > >(tee -a "$REL/deploy.log") 2>&1
echo "en-cours" >"$REL/status"
printf '%s\n' "$SHA" >"$REL/sha"
[[ -z "$ROLLED_FROM" ]] || echo "$ROLLED_FROM" >"$REL/rollback-of"
log "Version $(basename "$REL") ($MODE) par $(id -un) sur $(hostname)"
cp -R "$ARTIFACTS" "$REL/artifacts"
ARTIFACTS="$REL/artifacts"
for i in "${!WARS[@]}"; do WARS[i]="$ARTIFACTS/wars/$(basename "${WARS[i]}")"; done

# ---------------------------------------------------------------- briques
swap_dir() {
  # Remplace $2 par une copie de $1 en deux renommages (coupure de quelques millisecondes).
  # Pas de lien symbolique : le gestionnaire de fichiers d'Undertow ne les suit pas par défaut.
  local src="$1" dst="$2"
  rm -rf "${dst}.new" "${dst}.old"
  cp -R "$src" "${dst}.new"
  chmod -R u=rwX,go=rX "${dst}.new"
  if [[ -e "$dst" ]]; then mv "$dst" "${dst}.old"; fi
  mv "${dst}.new" "$dst"
  rm -rf "${dst}.old"
}

put_war() {
  # Dépose un WAR pour le scanner de WildFly : copie sous un nom qu'il ignore, renommage (atomique),
  # puis marqueur .dodeploy (marche que auto-deploy-zipped soit actif ou non).
  # Ne jamais effacer un .deployed : le scanner retirerait l'application.
  local src="$1" name
  name="$(basename "$src")"
  touch "$REL/state/stamp-$name"
  rm -f "${DEPLOYMENTS_DIR}/${name}.failed" "${DEPLOYMENTS_DIR}/${name}.skipdeploy" "${DEPLOYMENTS_DIR}/${name}.undeployed"
  cp "$src" "${DEPLOYMENTS_DIR}/${name}.uploading"
  chmod 0644 "${DEPLOYMENTS_DIR}/${name}.uploading"
  mv -f "${DEPLOYMENTS_DIR}/${name}.uploading" "${DEPLOYMENTS_DIR}/${name}"
  touch "${DEPLOYMENTS_DIR}/${name}.dodeploy"
}

wait_war() {
  # 0 : déployé après notre dépôt ; 1 : .failed ou délai dépassé.
  local name="$1" d="$DEPLOYMENTS_DIR" stamp="$REL/state/stamp-$1"
  local deadline=$((SECONDS + DEPLOY_TIMEOUT))
  while ((SECONDS < deadline)); do
    if [[ -f "$d/$name.failed" ]]; then
      log "WildFly refuse $name :"
      sed 's/^/    /' "$d/$name.failed" | head -n 20
      return 1
    fi
    if [[ -f "$d/$name.deployed" && "$d/$name.deployed" -nt "$stamp" \
          && ! -e "$d/$name.dodeploy" && ! -e "$d/$name.isdeploying" ]]; then
      return 0
    fi
    sleep "$POLL"
  done
  log "$name : pas de marqueur .deployed après ${DEPLOY_TIMEOUT} s (WildFly arrêté ? scanner désactivé ?)"
  return 1
}

compose() { bash "$REPO_DIR/deploy/compose.sh" "$@"; }

smoke_one() {
  local url host expect body
  IFS='|' read -r url host expect <<<"$1"
  body="$(mktemp)"
  local args=(-fsS -m 10 --noproxy "127.0.0.1,localhost" -o "$body")
  [[ -z "$host" ]] || args+=(-H "Host: $host")
  if curl "${args[@]}" "$url" 2>/dev/null && { [[ -z "$expect" ]] || grep -qiF -- "$expect" "$body"; }; then
    rm -f "$body"
    return 0
  fi
  rm -f "$body"
  return 1
}

smoke_all() {
  local deadline=$((SECONDS + SMOKE_TIMEOUT)) entry pending=("${SMOKE[@]}") left
  while ((${#pending[@]} > 0)); do
    left=()
    for entry in "${pending[@]}"; do
      if smoke_one "$entry"; then log "  ok   $entry"; else left+=("$entry"); fi
    done
    pending=("${left[@]+"${left[@]}"}")
    ((${#pending[@]} == 0)) && return 0
    if ((SECONDS >= deadline)); then
      for entry in "${pending[@]}"; do log "  ÉCHEC $entry"; done
      return 1
    fi
    sleep "$POLL"
  done
}

check_sbe_ml() {
  [[ "$CHECK_SBE_ML" != "0" ]] || return 0
  if ! command -v docker >/dev/null 2>&1; then
    [[ "$CHECK_SBE_ML" != "1" ]] || { log "docker absent"; return 1; }
    warn "docker absent : état de sbe-ml non contrôlé"
    return 0
  fi
  local cid status="inconnu" deadline=$((SECONDS + SBE_ML_TIMEOUT))
  cid="$(compose ps -q sbe-ml 2>/dev/null | head -n1)"
  if [[ -z "$cid" ]]; then
    log "le conteneur sbe-ml ne tourne pas (deploy/server/deploy-broker-ml.sh)"
    return 1
  fi
  while ((SECONDS < deadline)); do
    status="$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || echo inconnu)"
    case "$status" in
      healthy) log "  ok   sbe-ml healthy"; return 0 ;;
      unhealthy) log "  ÉCHEC sbe-ml unhealthy"; return 1 ;;
    esac
    sleep "$POLL"
  done
  log "  ÉCHEC sbe-ml encore « ${status} » après ${SBE_ML_TIMEOUT} s"
  return 1
}

summary() {
  [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] || return 0
  {
    echo "### Déploiement sur le serveur : $1"
    echo
    echo "| Version | Commit | État |"
    echo "| --- | --- | --- |"
    echo "| \`$(basename "$REL")\` | \`${SHA:0:7}\` | $(cat "$REL/status") |"
    echo
    echo "Journal complet sur le serveur : \`$REL/deploy.log\`"
  } >>"$GITHUB_STEP_SUMMARY"
}

rollback() {
  local ok=1 f w name old
  log "Retour à la version précédente"
  if [[ -f "$REL/state/git-moved" ]]; then
    old="$(cat "$REL/before/git-head")"
    git -C "$REPO_DIR" reset --hard --quiet "$old" && log "  dépôt revenu à ${old:0:7}" || ok=0
    if [[ -f "$REL/state/compose" ]]; then
      compose up -d --build && log "  services Docker reconstruits" || ok=0
    fi
  fi
  for f in "${FRONTS[@]}"; do
    [[ -f "$REL/state/front-$f" ]] || continue
    if [[ -d "$REL/before/$f" ]]; then
      swap_dir "$REL/before/$f" "$WEB_ROOT/$f" && log "  $f restauré" || ok=0
    else
      rm -rf "${WEB_ROOT:?}/$f" && mkdir -p "$WEB_ROOT/$f" || ok=0
    fi
  done
  for w in "${WARS[@]}"; do
    name="$(basename "$w")"
    [[ -f "$REL/state/war-$name" ]] || continue
    if [[ -f "$REL/before/wars/$name" ]]; then
      if put_war "$REL/before/wars/$name" && wait_war "$name"; then
        log "  $name : version précédente redéployée"
      else
        ok=0
      fi
    else
      # Pas de version précédente : on retire le WAR (le scanner le désinstalle).
      rm -f "${DEPLOYMENTS_DIR:?}/$name" "${DEPLOYMENTS_DIR}/$name.dodeploy" && log "  $name retiré" || ok=0
    fi
  done
  if ((ok)) && ((${#SMOKE[@]} > 0)); then
    log "Contrôles après le retour"
    smoke_all || { ok=0; log "  la version précédente ne répond pas non plus (problème hors de cette version ?)"; }
  fi
  if ((ok)); then
    echo "echec-retour-fait" >"$REL/status"
    log "Retour terminé : la version précédente est de nouveau en ligne."
  else
    echo "echec-retour-incomplet" >"$REL/status"
    log "Retour INCOMPLET : voir ce journal ($REL/deploy.log) et deploy/CI-CD.md, « Dépannage »."
  fi
}

on_exit() {
  local rc=$?
  if [[ "$PHASE" == "deploiement" ]]; then
    PHASE="retour"
    set +e
    trap - EXIT
    log "Déploiement interrompu (code $rc)."
    rollback
    summary "échec"
    exit 1
  fi
  exit "$rc"
}
trap on_exit EXIT
trap 'exit 130' INT TERM

prune() {
  local all=() cur n i
  mapfile -t all < <(release_dirs)
  cur="$(current_release)"
  n=${#all[@]}
  for ((i = 0; i < n - KEEP_RELEASES; i++)); do
    [[ "$(basename "${all[$i]}")" == "$cur" ]] && continue
    rm -rf "${all[$i]}"
  done
}

# ---------------------------------------------------------------- 1. photo de ce qui est en ligne
log "1/5 Photo de la version en ligne"
if [[ "$NO_GIT" -eq 0 ]]; then
  git -C "$REPO_DIR" rev-parse HEAD >"$REL/before/git-head"
fi
for f in "${FRONTS_IN[@]}"; do
  if [[ -d "$WEB_ROOT/$f" ]]; then cp -R "$WEB_ROOT/$f" "$REL/before/$f"; fi
done
for w in "${WARS[@]}"; do
  name="$(basename "$w")"
  if [[ -f "$DEPLOYMENTS_DIR/$name" ]]; then cp -p "$DEPLOYMENTS_DIR/$name" "$REL/before/wars/$name"; fi
done

PHASE="deploiement"

# ---------------------------------------------------------------- 2. dépôt du serveur
if [[ "$NO_GIT" -eq 0 ]]; then
  log "2/5 Dépôt du serveur -> ${SHA:0:7}"
  old="$(cat "$REL/before/git-head")"
  : >"$REL/changed-files"
  if [[ "$old" == "$SHA" ]]; then
    log "  déjà à ce commit"
  else
    if ! git -C "$REPO_DIR" cat-file -e "${SHA}^{commit}" 2>/dev/null; then
      git -C "$REPO_DIR" fetch --quiet "$GIT_REMOTE" || die "git fetch $GIT_REMOTE impossible (clé de déploiement ? deploy/CI-CD.md)"
    fi
    git -C "$REPO_DIR" cat-file -e "${SHA}^{commit}" 2>/dev/null || die "commit ${SHA} introuvable après git fetch"
    [[ -z "$(git -C "$REPO_DIR" status --porcelain --untracked-files=no)" ]] \
      || die "fichiers modifiés à la main dans $REPO_DIR (git status) : rien n'est écrasé, à régler d'abord"
    touch "$REL/state/git-moved"
    if git -C "$REPO_DIR" merge-base --is-ancestor "$old" "$SHA"; then
      git -C "$REPO_DIR" merge --ff-only --quiet "$SHA"
    elif [[ "$MODE" == "rollback" ]] && git -C "$REPO_DIR" merge-base --is-ancestor "$SHA" "$old"; then
      git -C "$REPO_DIR" reset --hard --quiet "$SHA"
    else
      die "${SHA:0:7} ne suit pas ${old:0:7} (historique de main réécrit ?) : pas d'avance rapide possible"
    fi
    git -C "$REPO_DIR" diff --name-only "$old" "$SHA" >"$REL/changed-files"
    log "  $(wc -l <"$REL/changed-files") fichier(s) changé(s)"
  fi
  if grep -Eq '^(iot/mlops/|docker-compose(\.[a-z]+)?\.yml$|deploy/compose\.sh$)' "$REL/changed-files"; then
    log "  code de sbe-ml ou fichiers compose modifiés : reconstruction"
    command -v docker >/dev/null 2>&1 || die "docker absent : impossible de reconstruire sbe-ml"
    touch "$REL/state/compose"
    compose up -d --build
  fi
  if grep -q '^infra/mqtt/' "$REL/changed-files"; then
    warn "infra/mqtt a changé : relancer à la main bash deploy/server/deploy-broker-ml.sh si le broker est concerné"
  fi
else
  log "2/5 Dépôt du serveur : ignoré (--no-git)"
fi

# ---------------------------------------------------------------- 3. PWA
log "3/5 PWA : ${FRONTS_IN[*]:-aucune}"
for f in "${FRONTS_IN[@]}"; do
  touch "$REL/state/front-$f"
  swap_dir "$ARTIFACTS/$f" "$WEB_ROOT/$f"
  log "  $WEB_ROOT/$f remplacé"
done

# ---------------------------------------------------------------- 4. WAR
WAR_NAMES=()
for w in "${WARS[@]}"; do WAR_NAMES+=("$(basename "$w")"); done
log "4/5 WAR : ${WAR_NAMES[*]:-aucun}"
for w in "${WARS[@]}"; do
  name="$(basename "$w")"
  touch "$REL/state/war-$name"
  put_war "$w"
  wait_war "$name" || die "$name n'a pas démarré"
  log "  $name déployé"
done

# ---------------------------------------------------------------- 5. contrôles
log "5/5 Contrôles"
if ((${#SMOKE[@]} > 0)); then
  smoke_all || die "contrôle HTTP en échec"
else
  warn "aucun contrôle HTTP défini"
fi
check_sbe_ml || die "sbe-ml n'est pas healthy"

PHASE="termine"
echo "ok" >"$REL/status"
basename "$REL" >"${RELEASES_DIR}/CURRENT"
prune
summary "réussi"
log "En ligne : $(basename "$REL")"
