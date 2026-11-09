#!/usr/bin/env bash
# Compile le moteur NetBird (netbird/client/android, avec nos patchs) en AAR
# Android par gomobile bind.
#
# Usage : scripts/build-netbird-aar.sh [applicationId] [sortie.aar]
#   applicationId : paquet de l'app qui embarque le moteur, pour le répertoire
#                   du socket UAPI (défaut : build fdroid debug de TrackerControl)
#   sortie        : défaut build/netbird/netbird.aar
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/scripts/env.sh"

APP_ID=${1:-net.kollnig.missioncontrol.fdroid.test}
OUT=${2:-$ROOT/build/netbird/netbird.aar}
# minSdk de TrackerControl (app/build.gradle) : l'AAR ne doit pas exiger plus.
ANDROID_API=23
# arm64 : téléphone ; amd64 : émulateur. NETBIRD_TARGETS pour en changer.
TARGETS=${NETBIRD_TARGETS:-android/arm64,android/amd64}

cd "$ROOT/netbird"

# gomobile et gobind doivent correspondre au pin golang.org/x/mobile du go.mod
# (même règle que build-android-lib.sh du client NetBird).
want=$(go list -m -f '{{.Version}}' golang.org/x/mobile)
for tool in gomobile gobind; do
  have=$(go version -m "$(command -v "$tool")" 2>/dev/null \
    | awk '$1 == "mod" && $2 == "golang.org/x/mobile" {print $3}') || true
  if [ "$have" != "$want" ]; then
    echo "Installation de $tool au pin $want (trouvé : ${have:-aucun})"
    go install "golang.org/x/mobile/cmd/$tool@$want"
  fi
done

base=$(git -C "$ROOT" log -1 --format=%s --grep="^Squashed 'netbird/'" | grep -o '[0-9a-f]\{7,\}$' || true)
patch=$(git -C "$ROOT" rev-parse --short HEAD)
version="0.82.0-tc+${patch}"
echo "NetBird ${version} (amont ${base:-?}), API ${ANDROID_API}, ${TARGETS}"

mkdir -p "$(dirname "$OUT")"
sockdir="/data/data/${APP_ID}/cache/wireguard"
CGO_ENABLED=0 gomobile bind \
  -target "$TARGETS" \
  -androidapi "$ANDROID_API" \
  -o "$OUT" \
  -javapkg=io.netbird.gomobile \
  -ldflags="-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384 -checklinkname=0 \
-X github.com/netbirdio/wireguard-go/ipc.socketDirectory=${sockdir} \
-X golang.zx2c4.com/wireguard/ipc.socketDirectory=${sockdir} \
-X github.com/netbirdio/netbird/version.version=${version}" \
  "$(pwd)/client/android"

ls -l "$OUT"
