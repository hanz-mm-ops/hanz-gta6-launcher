#!/bin/zsh
# Set the home screen's dock (up to 6) and the row of apps below it.
# Use package names, or @mirror, @inputs, @settings for the built-in tiles.
# Usage: tools/set-dock.sh <tv-ip> "<dock,comma,separated>" ["<below,dock>"]
set -e
[[ -n "$2" ]] || { echo "Usage: $0 <tv-ip> \"pkg1,pkg2,...\" [\"pkgA,pkgB\"]"; exit 1; }
# Accepts "ip" (classic port 5555) or "ip:port" (Wireless debugging).
export ANDROID_SERIAL="$1"; [[ "$1" == *:* ]] || ANDROID_SERIAL="$1:5555"
adb connect "$ANDROID_SERIAL" >/dev/null
args=(--es app.tvshell.DOCK "$2")
[[ -n "$3" ]] && args+=(--es app.tvshell.GRID "$3")
adb shell am start -n app.tvshell/.HomeActivity "${args[@]}" >/dev/null
echo "Dock updated."
