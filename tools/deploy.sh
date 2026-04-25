#!/usr/bin/env bash
# ASUSon, a projekt gyökeréből:  assembleDebug  →  adb install -r  →  am start
# Ugyanaz a cél, mint a Macről: build-on-asus.sh utolsó lépései.
# Skip telepítés:  SKIP_ADB=1 ./tools/deploy.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export PATH="${HOME}/.local/java/current/bin:${HOME}/Android/Sdk/platform-tools:${PATH}"
if [[ ! -f local.properties ]]; then
  if [[ -f "$HOME/Projects/hash-app/local.properties" ]]; then
    cp -f "$HOME/Projects/hash-app/local.properties" .
  else
    echo "sdk.dir=${HOME}/Android/Sdk" > local.properties
  fi
fi
chmod +x ./gradlew
./gradlew :app:assembleDebug --no-daemon
if [[ "${SKIP_ADB:-}" == "1" ]]; then
  echo "▶ SKIP_ADB=1 — telepítés kihagyva"
  exit 0
fi
exec bash tools/adb-install-and-start.sh
