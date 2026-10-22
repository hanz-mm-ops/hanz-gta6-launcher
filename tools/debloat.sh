#!/bin/zsh
# Disable (never uninstall) the packages listed in a file, one per line; '#' starts a comment.
# Every package stays on the TV and can be re-enabled with tools/restore.sh.
# Usage: tools/debloat.sh <tv-ip> <package-list-file>
set -e
[[ -n "$2" ]] || { echo "Usage: $0 <tv-ip> <package-list-file>"; exit 1; }
# Accepts "ip" (classic port 5555) or "ip:port" (Wireless debugging).
export ANDROID_SERIAL="$1"; [[ "$1" == *:* ]] || ANDROID_SERIAL="$1:5555"
adb connect "$ANDROID_SERIAL" >/dev/null
grep -v '^\s*#' "$2" | awk '{print $1}' | grep . | while read p; do
  printf "%-50s " "$p"
  adb shell pm disable-user --user 0 "$p" 2>&1 | tail -1
done
