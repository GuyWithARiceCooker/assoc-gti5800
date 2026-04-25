#!/usr/bin/env bash
# Futtatás: ASUSon (a projekt mappájából vagy $HOME/assoc-gti5800)
# Telepíti a debug APK-t, majd elindítja.
#
# Eszközválasztás:
#   ADB_SERIAL=<sor>     — kényszerített (leghatározottabb; pl. wireless sora)
#   PREFER_WIRELESS=1    — első „wifi” sorból választ (lásd lentebb)
#   (alap)               — USB ( *_adb-tls-connect_* sorok kizárva, pl. Galaxy kábel)
#
# --- Wi-Fi ADB (fejlesztés, nincs kábel) — röviden
# 1) Telefon: Fejlesztői beállítások → Vezeték nélküli hibakeresés (Android 11+), vagy
#    USB egyszer: adb tcpip 5555, kábel kihúz, majd ugyanazon LAN-on:
#    adb connect <telefon-LAN-IP>:5555
# 2) Párosítás 11+ (első indításkor): a telefonon megjelenő „Párosítás” IP:port + kóddal:
#    adb pair <párosító-IP:port>   # majd
#    adb connect <csatlakozási-IP:port>  # a wireless képernyőn látod mindkettőt
# 3) Az ASUS/PC és a telefon ugyanazon Wi-Fi-n legyen; tűzfal ne blokkolja a portot.
# 4) Telepítés:  PREFER_WIRELESS=1  ./tools/adb-install-and-start.sh  (vagy ADB_SERIAL=... )
set -euo pipefail
export PATH="${PATH}:${HOME}/Android/Sdk/platform-tools"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$APK" ]]; then
  echo "Nem találom az APK-t: $APK" >&2
  exit 1
fi
echo "▶ adb devices"
adb devices

pick_device_serial() {
  if [[ -n "${ADB_SERIAL:-}" ]]; then
    echo "$ADB_SERIAL"
    return
  fi
  if [[ "${PREFER_WIRELESS:-}" == "1" ]]; then
    # mDNS (adb-tls-connect) vagy manuális adb connect → IP:port alak
    adb devices 2>/dev/null | awk 'NF && $1!="List" && $2=="device" && ( $0 ~ /_adb-tls-connect/ || $1 ~ /^[0-9]+\.[0-9]+/ ) { print $1; exit }'
    return
  fi
  adb devices 2>/dev/null | awk 'NF && $1!="List" && $2=="device" && $0 !~ /_adb-tls-connect/ { print $1; exit }'
}

SER="$(pick_device_serial)"
if [[ -z "$SER" ]]; then
  if [[ -n "${ADB_SERIAL:-}" ]]; then
    echo "ADB_SERIAL=$ADB_SERIAL nincs „device” állapotban az adb listában." >&2
  elif [[ "${PREFER_WIRELESS:-}" == "1" ]]; then
    echo "Nincs Wi-Fi ADB eszköz a listán (párosíts: adb pair …, adb connect …)." >&2
    echo "  Vagy: ADB_SERIAL=<a sor, pl. 192.168.0.12:37301 vagy *adb-tls*>" >&2
  else
    echo "Nincs USB ADB készülék (authorized + „device”)." >&2
    echo "  A wireless ( *_adb-tls-connect_* ) kihagyva — Wi-Fi-hez:  PREFER_WIRELESS=1  $0" >&2
    echo "  Galaxy: kábel az ASUShoz … vagy: ADB_SERIAL=…" >&2
  fi
  exit 1
fi

echo "▶ install -> ${SER}"
adb -s "${SER}" install -r "$APK"
echo "▶ am start tomi.assoc.gti5800/.AssocActivity"
adb -s "${SER}" shell am start -n tomi.assoc.gti5800/.AssocActivity
echo "▶ kész"
