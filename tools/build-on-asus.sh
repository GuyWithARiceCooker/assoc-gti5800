#!/usr/bin/env bash
# Futtatás: Macről, ha megy az SSH (`asus-ubuntu` = ~/.ssh/config)
#   ~/assoc-gti5800/tools/build-on-asus.sh
# rsync + Gradle assembleDebug, utána rögtön adb install + am start (tools/adb-install-and-start.sh).
# Csak build, telepítés nélkül:  SKIP_ADB=1  ~/assoc-gti5800/tools/build-on-asus.sh

set -euo pipefail
REMOTE="${REMOTE:-asus-ubuntu}"
PROJ="assoc-gti5800"
SRC="${1:-$HOME/assoc-gti5800}"
DEST="~/${PROJ}/"

if [[ ! -d "$SRC" ]]; then
  echo "Nem találom: $SRC" >&2
  exit 1
fi

echo "▶ rsync -> ${REMOTE}:${DEST}"
rsync -avz --delete \
  --exclude '.git' \
  --exclude '.gradle' \
  --exclude 'build' \
  --exclude 'app/build' \
  --exclude 'local.properties' \
  "$SRC/" "${REMOTE}:${DEST}"

echo "▶ build az ASUSon (SKIP_ADB, PREFER_WIRELESS továbbítva)"
# SKIP_ADB=1  (Mac) → távoli build után nincs adb
# PREFER_WIRELESS=1  (Mac) → tools/adb-install-and-start.sh a Wi-Fi ADB sorból válasszon (kábel helyett)
ssh -o BatchMode=yes "$REMOTE" "export SKIP_ADB='${SKIP_ADB:-}'; export PREFER_WIRELESS='${PREFER_WIRELESS:-}'; bash -s" <<'REMOTE'
set -euo pipefail
export PATH="${HOME}/.local/java/current/bin:${PATH}"
cd "$HOME/assoc-gti5800" || { echo "Nincs ~/assoc-gti5800" >&2; exit 1; }
if [[ ! -f local.properties ]]; then
  if [[ -f "$HOME/Projects/hash-app/local.properties" ]]; then
    cp -f "$HOME/Projects/hash-app/local.properties" .
  elif [[ -d "$HOME/Android/Sdk" ]]; then
    echo "sdk.dir=${HOME}/Android/Sdk" > local.properties
  else
    echo "Hiányzik local.properties és nincs ~/Android/Sdk" >&2
    exit 1
  fi
fi
chmod +x ./gradlew 2>/dev/null || true
./gradlew :app:assembleDebug --no-daemon
APK="app/build/outputs/apk/debug/app-debug.apk"
ls -la "$APK"
echo "▶ kész: $HOME/assoc-gti5800/$APK"
if [[ "${SKIP_ADB:-}" == "1" ]]; then
  echo "▶ SKIP_ADB=1 — telepítés / indítás kihagyva"
  exit 0
fi
export PATH="${HOME}/Android/Sdk/platform-tools:${PATH}"
if [[ -f tools/adb-install-and-start.sh ]]; then
  chmod +x tools/adb-install-and-start.sh 2>/dev/null || true
  echo "▶ telepítés + AssocActivity (USB, wireless OnePlus kihagyva: adb-install-and-start.sh)"
  bash tools/adb-install-and-start.sh
else
  echo "Nincs tools/adb-install-and-start.sh" >&2
  exit 1
fi
REMOTE
