#!/bin/bash
# PocketOrca-Chat APK build — manual aapt2 + d8 chain (no Gradle).
#
# Requirements:
#   - JDK 17+ (`java`, `javac`, `jar` on PATH)
#   - Android build-tools >= 34 (aapt2, d8, zipalign, apksigner)
#   - android.jar for API 36
#   - a keystore to sign with
#
# Configure via environment variables:
#   SDK_BUILD_TOOLS  path to build-tools dir  (e.g. ~/android/sdk/build-tools/36.0.0)
#   ANDROID_JAR      path to android.jar      (e.g. ~/android/sdk/platforms/android-36/android.jar)
#   KS               keystore path
#   KS_PASS          keystore password
#   KEY_ALIAS        key alias
#   VC               versionCode (default 43)
#   VN               versionName (default 0.1.2)
#
# Usage: VC=43 VN=0.1.2 ./build.sh
#
# The script ends with an integrity check: assets/app.js inside the APK must be
# byte-identical to the source file, otherwise the build fails.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
APP=$ROOT/app/src/main
OUT=$ROOT/build

: "${SDK_BUILD_TOOLS:?set SDK_BUILD_TOOLS to your Android build-tools directory}"
: "${ANDROID_JAR:?set ANDROID_JAR to your android.jar}"
: "${KS:?set KS to your keystore path}"
: "${KS_PASS:?set KS_PASS}"
: "${KEY_ALIAS:?set KEY_ALIAS}"

VC=${VC:-43}
VN=${VN:-0.1.2}
ARTIFACT=$ROOT/PocketOrca-Chat-v$VN-vc$VC-release.apk

rm -rf "$OUT/classes" "$OUT/dex" "$OUT/res.zip" "$OUT/base.apk" "$OUT/aligned.apk"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== javac =="
javac --release 11 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/classes" "$APP"/java/com/pocketorca/chat/*.java
[ -f "$OUT/classes/com/pocketorca/chat/MainActivity.class" ] || { echo JAVAC_FAILED; exit 1; }

echo "== d8 =="
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$SDK_BUILD_TOOLS/d8" --release --lib "$ANDROID_JAR" --min-api 29 --output "$OUT/dex" "$OUT/classes.jar"
[ -f "$OUT/dex/classes.dex" ] || { echo D8_FAILED; exit 1; }

echo "== aapt2 compile res =="
"$SDK_BUILD_TOOLS/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "== aapt2 link =="
"$SDK_BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --min-sdk-version 29 --target-sdk-version 36 \
  --version-code "$VC" --version-name "$VN" \
  "$OUT/res.zip"

echo "== add dex =="
(cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex)

echo "== zipalign =="
"$SDK_BUILD_TOOLS/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== sign =="
"$SDK_BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --out "$ARTIFACT" "$OUT/aligned.apk"
"$SDK_BUILD_TOOLS/apksigner" verify "$ARTIFACT" && echo VERIFY_OK

echo "== artifact =="
ls -la "$ARTIFACT"
sha256sum "$ARTIFACT"

echo "== assets integrity check =="
SRC_JS=$(sha256sum "$APP/assets/app.js" | cut -d' ' -f1)
PKG_JS=$(unzip -p "$ARTIFACT" assets/app.js | sha256sum | cut -d' ' -f1)
echo "src=$SRC_JS"
echo "pkg=$PKG_JS"
[ "$SRC_JS" = "$PKG_JS" ] && echo ASSETS_OK || { echo ASSETS_MISMATCH; exit 1; }
