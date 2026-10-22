#!/bin/zsh
# Build the home screen APK with the plain Android SDK tools (no Gradle needed).
set -euo pipefail
cd "${0:A:h}"

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
# Homebrew's android-commandlinetools / openjdk@17, when Android Studio isn't installed.
[[ -d $SDK/build-tools ]] || SDK=/opt/homebrew/share/android-commandlinetools
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
[[ -x $JAVA_HOME/bin/javac ]] || JAVA_HOME=/opt/homebrew/opt/openjdk@17
OUT=build
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

rm -rf $OUT && mkdir -p $OUT/res $OUT/gen $OUT/classes

# Rockstar's artwork, logo and video aren't in the repo; download them on first build.
for f in res/drawable-nodpi/vice_background.jpg res/drawable-nodpi/vice_art_{1..10}.jpg \
    res/drawable-nodpi/vice_logo.png res/raw/vice_skyline.mp4; do
  [[ -e $f ]] || { ./fetch-art.sh; break; }
done

echo "• resources"
$BT/aapt2 compile --dir res -o $OUT/res.zip
$BT/aapt2 link -o $OUT/unsigned.apk -I $JAR --manifest AndroidManifest.xml \
  --java $OUT/gen --min-sdk-version 30 --target-sdk-version 34 $OUT/res.zip

echo "• java"
"$JAVA_HOME/bin/javac" -nowarn --release 17 -encoding UTF-8 -classpath $JAR -d $OUT/classes \
  $(find src $OUT/gen -name '*.java')

echo "• dex"
$BT/d8 --release --min-api 30 --lib $JAR --output $OUT $(find $OUT/classes -name '*.class')
(cd $OUT && zip -q -j unsigned.apk classes.dex)

echo "• sign"
# First build on this computer: make the standard Android debug key.
if [[ ! -f $HOME/.android/debug.keystore ]]; then
  mkdir -p $HOME/.android
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore $HOME/.android/debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
$BT/zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
$BT/apksigner sign \
  --ks $HOME/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out $OUT/Home.apk $OUT/aligned.apk

ls -lh $OUT/Home.apk
