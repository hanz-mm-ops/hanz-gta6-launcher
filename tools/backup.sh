#!/bin/zsh
# Snapshot everything we might change: package lists, settings, current home screen,
# and copies of the APKs named on the command line. Nothing on the TV is modified.
# Usage: tools/backup.sh <tv-ip> [package ...]
set -e
[[ -n "$1" ]] || { echo "Usage: $0 <tv-ip> [package ...]"; exit 1; }
# Accepts "ip" (classic port 5555) or "ip:port" (Wireless debugging).
export ANDROID_SERIAL="$1"; [[ "$1" == *:* ]] || ANDROID_SERIAL="$1:5555"; shift
adb connect "$ANDROID_SERIAL" >/dev/null
D="backups/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$D/apks"
adb shell pm list packages -f -U > "$D/packages-all.txt"
adb shell pm list packages -d > "$D/packages-disabled.txt"
adb shell pm list packages -3 > "$D/packages-thirdparty.txt"
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$D/home-launcher.txt"
for n in global secure system; do adb shell settings list $n > "$D/settings-$n.txt"; done
adb shell getprop > "$D/getprop.txt"
for p in "$@"; do
  apk=$(adb shell pm path "$p" | head -1 | sed 's/package://' | tr -d '\r')
  [[ -n "$apk" ]] && adb pull "$apk" "$D/apks/$p.apk" >/dev/null && echo "saved $p"
done
echo "Backup written to $D"
