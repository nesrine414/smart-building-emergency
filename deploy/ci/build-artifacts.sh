#!/usr/bin/env bash
# Rassemble ce que le job « deploy » installe sur le serveur (étape 5, personne 1).
# Lancé par le job « build » du pipeline, après mvn -B -ntp -DskipTests package :
#     bash deploy/ci/build-artifacts.sh <dossier de sortie>
# Marche aussi sur un PC (bash, Node 22) pour vérifier qu'un build propre passe.
#
# Sortie :
#     wars/api.war  wars/iam.war          (Maven, dossiers target/)
#     frontoffice/                        (frontend/client-pwa, npm ci + build de production)
#     backoffice/                         (frontend/admin-pwa,  npm ci + build de production)
#     BUILD_INFO                          (commit, date, numéro d'exécution)
# Variables : SKIP_PWA=1 pour ne pas construire les PWA (essais), REQUIRE_PWA=0 pour accepter une
# PWA absente du dépôt (sinon c'est une erreur).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${1:?usage: build-artifacts.sh <dossier de sortie>}"
SKIP_PWA="${SKIP_PWA:-0}"
REQUIRE_PWA="${REQUIRE_PWA:-1}"

die() {
  echo "ERREUR : $*" >&2
  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then echo "::error::$*"; fi
  exit 1
}

rm -rf "$OUT"
mkdir -p "$OUT/wars"
OUT="$(cd "$OUT" && pwd)"

for module in iam api; do
  war="$ROOT/$module/target/$module.war"
  [[ -f "$war" ]] || die "$war introuvable : lancer d'abord mvn -B -ntp -DskipTests package"
  cp "$war" "$OUT/wars/"
  echo "WAR   $module.war ($(du -h "$war" | cut -f1))"
done

build_pwa() {
  local app="$1" target="$2" dir="$ROOT/frontend/$1"
  if [[ ! -f "$dir/package.json" ]]; then
    [[ "$REQUIRE_PWA" == "0" ]] || die "frontend/$app/package.json introuvable"
    echo "PWA   $app absente : ignorée (REQUIRE_PWA=0)"
    return 0
  fi
  [[ -f "$dir/package-lock.json" ]] || die "frontend/$app/package-lock.json manquant : npm ci en a besoin"
  echo "PWA   $app : npm ci puis build de production"
  (
    cd "$dir"
    rm -rf dist
    npm ci --no-audit --no-fund
    npm run build -- --configuration production
  )
  # Angular 21 (builder @angular/build:application) écrit dans dist/<projet>/browser/
  local outs=()
  mapfile -t outs < <(find "$dir/dist" -mindepth 2 -maxdepth 2 -type d -name browser)
  [[ ${#outs[@]} -eq 1 ]] || die "$app : un seul dossier dist/<projet>/browser attendu, trouvé ${#outs[@]}"
  [[ -f "${outs[0]}/index.html" ]] || die "$app : pas d'index.html dans ${outs[0]}"
  cp -R "${outs[0]}" "$OUT/$target"
  echo "PWA   $app -> $target ($(find "$OUT/$target" -type f | wc -l) fichiers)"
}

if [[ "$SKIP_PWA" == "1" ]]; then
  echo "PWA   ignorées (SKIP_PWA=1)"
else
  build_pwa client-pwa frontoffice
  build_pwa admin-pwa backoffice
fi

{
  echo "sha=${GITHUB_SHA:-$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo inconnu)}"
  echo "ref=${GITHUB_REF_NAME:-$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo inconnu)}"
  echo "run=${GITHUB_RUN_NUMBER:-local}"
  echo "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$OUT/BUILD_INFO"
echo "Artefacts prêts dans $OUT :"
cat "$OUT/BUILD_INFO"
