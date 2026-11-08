#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
JAR="$HOME/sdk/android-11/android.jar"
B=build
rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
aapt2 compile --dir res -o "$B/res.zip"
aapt2 link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A assets --min-sdk-version 30 --target-sdk-version 30 "$B/res.zip"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
d8 --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
apksigner sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/unsigned.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
