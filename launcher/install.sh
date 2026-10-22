#!/bin/zsh
# Build (if needed), install and grant the home screen, then make it the default.
# The TV's original launcher is left enabled; disable it only after this one works.
# Usage: launcher/install.sh <tv-ip>
set -e
cd "${0:A:h}"
[[ -n "$1" ]] || { echo "Usage: $0 <tv-ip>"; exit 1; }
# Accepts "ip" (classic port 5555) or "ip:port" (Wireless debugging).
export ANDROID_SERIAL="$1"; [[ "$1" == *:* ]] || ANDROID_SERIAL="$1:5555"
adb connect "$ANDROID_SERIAL" >/dev/null

[[ -f build/Home.apk ]] || ./build.sh
echo "• installing"
adb install -r --no-incremental build/Home.apk

echo "• permissions (screen saver settings, Wi-Fi name, paired remotes)"
adb shell pm grant app.tvshell android.permission.WRITE_SECURE_SETTINGS || true
adb shell appops set app.tvshell WRITE_SETTINGS allow || true
adb shell pm grant app.tvshell android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant app.tvshell android.permission.BLUETOOTH_CONNECT || true
echo "• permissions (Continue Watching: Watch Next, recently used apps)"
adb shell pm grant app.tvshell com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null || true
adb shell appops set app.tvshell GET_USAGE_STATS allow || true

echo "• screen saver"
adb shell settings put secure screensaver_components app.tvshell/.AerialDream
adb shell settings put secure screensaver_enabled 1

echo "• default home"
adb shell cmd package set-home-activity app.tvshell/.HomeActivity
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Done. Press Home on the remote."
