#!/bin/zsh
# Undo debloat.sh and the home-screen swap: re-enable the listed packages and make the
# given launcher (the TV's original one) the default home again.
# Usage: tools/restore.sh <tv-ip> <package-list-file> <original-home-activity>
#   e.g. tools/restore.sh 192.168.1.50 my-bloat.txt com.vendor.launcher/.MainActivity
set -e
[[ -n "$3" ]] || { echo "Usage: $0 <tv-ip> <package-list-file> <original-home-activity>"; exit 1; }
# Accepts "ip" (classic port 5555) or "ip:port" (Wireless debugging).
export ANDROID_SERIAL="$1"; [[ "$1" == *:* ]] || ANDROID_SERIAL="$1:5555"
adb connect "$ANDROID_SERIAL" >/dev/null
grep -v '^\s*#' "$2" | awk '{print $1}' | grep . | while read p; do
  adb shell cmd package install-existing "$p" >/dev/null 2>&1 || true
  printf "%-50s " "$p"; adb shell pm enable --user 0 "$p" 2>&1 | tail -1
done
adb shell pm enable --user 0 "${3%%/*}" >/dev/null 2>&1 || true
adb shell cmd package set-home-activity "$3"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global $k 1
done
echo "Restored. Press Home on the TV."
