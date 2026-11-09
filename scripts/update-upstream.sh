#!/usr/bin/env bash
# Suit les versions PUBLIÉES en amont (TrackerControl, NetBird) et fusionne la plus récente
# par `git subtree pull --squash`. Source de vérité : upstream-versions.env.
#
# Usage : scripts/update-upstream.sh [tc|nb|all] [--dry-run]
# Codes de sortie : 0 mis à jour, 10 rien à faire, 20 conflit de fusion (état laissé pour
# inspection), 1 erreur.
# Variables : UPSTREAM_REPORT=fichier pour recevoir des lignes « tc=<étiquette> » / « nb=<étiquette> »
# des projets mis à jour ; GH_TOKEN/GITHUB_TOKEN pour l'API GitHub.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
VERSIONS=$ROOT/upstream-versions.env

which=all; dry=0
for a in "$@"; do
  case $a in
    tc|nb|all) which=$a ;;
    --dry-run) dry=1 ;;
    *) echo "Usage : $0 [tc|nb|all] [--dry-run]" >&2; exit 1 ;;
  esac
done

# shellcheck disable=SC1090
. "$VERSIONS"

api() {  # api <chemin> : JSON de l'API GitHub
  local tok=${GH_TOKEN:-${GITHUB_TOKEN:-}}
  if command -v gh >/dev/null 2>&1 && [ -n "$tok" ]; then
    GH_TOKEN=$tok gh api "$1"
  else
    curl -fsSL ${tok:+-H "Authorization: Bearer $tok"} -H 'Accept: application/vnd.github+json' "https://api.github.com/$1"
  fi
}

repo_slug() { sed -E 's#^https://github.com/(.*)\.git$#\1#' <<<"$1"; }

latest_tag() {  # latest_tag <depôt> : dernière release non pre-release
  api "repos/$(repo_slug "$1")/releases/latest" | jq -r 'select(.prerelease == false and .draft == false) | .tag_name'
}

# plus_recent <a> <b> : vrai si a > b
plus_recent() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }

updated=()
sed_env() {  # sed_env <VAR> <valeur>
  sed -i -E "s|^$1=.*|$1=$2|" "$VERSIONS"
}

traite() {  # traite <clé> <nom> <dossier> <dépôt> <actuelle> <regex d'étiquette>
  local key=$1 name=$2 dir=$3 repo=$4 cur=$5 re=$6 tag
  tag=$(latest_tag "$repo") || { echo "Erreur : impossible d'interroger $name ($repo)" >&2; return 1; }
  if [[ -z $tag || ! $tag =~ $re ]]; then
    echo "Erreur : étiquette inattendue pour $name : '${tag:-vide}'" >&2; return 1
  fi
  if ! plus_recent "$tag" "$cur"; then
    echo "$name : à jour (actuelle $cur, dernière publiée $tag)"
    return 0
  fi
  echo "$name : $cur -> $tag"
  if [ "$dry" = 1 ]; then
    echo "  [dry-run] git subtree pull --prefix=$dir $repo $tag --squash"
    updated+=("$key=$tag"); return 0
  fi
  if ! git subtree pull --prefix="$dir" "$repo" "$tag" --squash \
        -m "Amont : $name $tag (subtree squash)"; then
    echo "Conflit lors de la fusion de $name $tag. Fichiers en conflit :" >&2
    git diff --name-only --diff-filter=U >&2 || true
    echo "État de fusion laissé pour inspection." >&2
    exit 20
  fi
  sed_env "${key^^}_RELEASE" "$tag"
  if [ "$key" = tc ]; then
    local sha
    sha=$(git ls-remote "$repo" "refs/tags/$tag^{}" "refs/tags/$tag" | awk 'NR==1{print $1}')
    if [ -n "$sha" ]; then sed_env TC_IMPORTED "$sha"; fi
  fi
  git add "$VERSIONS"
  git commit -q -m "Amont : versions suivies mises à jour ($name $tag)"
  updated+=("$key=$tag")
}

if [ "$dry" = 0 ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Erreur : l'arbre de travail n'est pas propre." >&2; exit 1
fi

if [ "$which" = tc ] || [ "$which" = all ]; then
  traite tc TrackerControl trackercontrol "$TC_REPO" "$TC_RELEASE" '^[0-9]{10}$'
fi
if [ "$which" = nb ] || [ "$which" = all ]; then
  traite nb NetBird netbird "$NB_REPO" "$NB_RELEASE" '^v[0-9]+\.[0-9]+\.[0-9]+$'
fi

if [ "${#updated[@]}" = 0 ]; then
  echo "Rien à faire."
  exit 10
fi
if [ -n "${UPSTREAM_REPORT:-}" ]; then printf '%s\n' "${updated[@]}" > "$UPSTREAM_REPORT"; fi
if [ "$dry" = 1 ]; then echo "[dry-run] ${#updated[@]} mise(s) à jour disponible(s) : ${updated[*]}"; fi
exit 0
