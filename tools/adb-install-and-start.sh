#!/usr/bin/env bash
# Futtatás: ASUSon (a projekt mappájából vagy $HOME/assoc-gti5800)
# Telepíti a debug APK-t, majd elindítja.
#
# Alapból NEM a wireless (OnePlus / adb-tls-connect) készülékre megy, hanem
# USB-n csatlakozó, „device” készülékre (pl. Galaxy GT-I5800).
# Kényszeríthető: ADB_SERIAL=<sor> ./adb-install-and-start.sh
set -euo pipefail
export PATH="${PATH}:${HOME}/Android/Sdk/platform-tools"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$APK" ]]; then
  echo "Nem találom az APK-t: $APK" >&2
  exit 1
fi
echo "▶ adb devices"
adb devices

pick_usb_serial() {
  if [[ -n "${ADB_SERIAL:-}" ]]; then
    echo "$ADB_SERIAL"
    return
  fi
  adb devices 2>/dev/null | awk 'NF && $1!="List" && $2=="device" && $0 !~ /_adb-tls-connect/ { print $1; exit }'
}

SER="$(pick_usb_serial)"
if [[ -z "$SER" ]]; then
  if [[ -n "${ADB_SERIAL:-}" ]]; then
    echo "ADB_SERIAL=$ADB_SERIAL nincs „device” állapotban az adb listában." >&2
  else
    echo "Nincs USB ADB készülék (authorized + „device”)." >&2
    echo "  A wireless/OnePlus ( *_adb-tls-connect_* ) kihagyva — ne arra tedd, kérés szerint." >&2
    echo "  Galaxy: kábel az ASUShoz, USB hibakeresés, (udev) → újra adb devices. Vagy: ADB_SERIAL=…" >&2
  fi
  exit 1
fi

echo "▶ install -> ${SER} (OnePlus/wireless kihagyva, hacsak nem ADB_SERIAL)"
adb -s "${SER}" install -r "$APK"
echo "▶ am start tomi.assoc.gti5800/.AssocActivity"
adb -s "${SER}" shell am start -n tomi.assoc.gti5800/.AssocActivity
echo "▶ kész"
