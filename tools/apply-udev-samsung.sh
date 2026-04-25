#!/usr/bin/env bash
# Egyszer az ASUSon (helyi terminál, kábel bedugva): jelszót kér a sudo.
# Futtatás:  bash ~/assoc-gti5800/tools/apply-udev-samsung.sh
set -euo pipefail
R="$(cd "$(dirname "$0")" && pwd)/70-android-samsung-adb.rules"
DEST="/etc/udev/rules.d/70-android-samsung-adb.rules"
if [[ ! -f "$R" ]]; then
  echo "Nem találom: $R" >&2
  exit 1
fi
echo "Másolás -> $DEST (sudo)"
sudo cp -f "$R" "$DEST"
sudo udevadm control --reload-rules
sudo udevadm trigger
echo "Kész. Húzd ki/bedugd a telefont USB-vel, majd: adb devices"
export PATH="${PATH}:${HOME}/Android/Sdk/platform-tools"
adb kill-server
sleep 1
adb start-server
adb devices
