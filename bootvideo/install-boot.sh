#!/bin/zsh
# Optional: replace the startup video on HiSilicon TVs that play /atv/bootvideo/bootvideo.mp4.
# Check first:  adb shell ls -la /atv/bootvideo   (the files must be world-writable)
# Usage: bootvideo/install-boot.sh <tv-ip>             install the new 3 s video + logo
#        bootvideo/install-boot.sh <tv-ip> --restore   put the originals back
set -e
cd "${0:A:h}"
[[ -n "$1" ]] || { echo "Usage: $0 <tv-ip> [--restore]"; exit 1; }
export ANDROID_SERIAL="$1:5555"
adb connect "$ANDROID_SERIAL" >/dev/null
ORIG=originals

# adb can't re-own these files, so overwrite their contents in place.
put() {
  adb push "$1" /data/local/tmp/boot.tmp >/dev/null
  adb shell "cat /data/local/tmp/boot.tmp > /atv/bootvideo/$2 && rm /data/local/tmp/boot.tmp"
}

if [[ "$2" == "--restore" ]]; then
  put "$ORIG/bootvideo.mp4" bootvideo.mp4; put "$ORIG/logo.jpg" logo.jpg
  echo "Original startup video restored."; exit
fi

if [[ ! -f "$ORIG/bootvideo.mp4" ]]; then
  mkdir -p "$ORIG"
  adb pull /atv/bootvideo/bootvideo.mp4 "$ORIG/" >/dev/null && adb pull /atv/bootvideo/logo.jpg "$ORIG/" >/dev/null
  echo "Backed up the originals to bootvideo/$ORIG"
fi
[[ -f bootvideo.mp4 ]] || { swiftc -O render.swift -o render && ./render .; }
put bootvideo.mp4 bootvideo.mp4; put logo.jpg logo.jpg
echo "Installed. It plays on the next power-on."
