#!/usr/bin/env bash
# Builds release + debug APKs, validates them, and copies deliverables to dist/.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source .tooling/dev-env.sh
DIST="$ROOT/dist"
mkdir -p "$DIST"

./scripts/gradle.sh :app:assembleDebug -PallAbis --console=plain
./scripts/gradle.sh :app:assembleRelease --console=plain

BT="$ANDROID_HOME/build-tools/36.0.0"
AAPT2="$BT/aapt2"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
DEBUG_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="$ROOT/app/build/outputs/apk/release/app-release.apk"

VERSION="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
cp "$DEBUG_APK" "$DIST/PartFlasher-${VERSION}-arm64+x86_64-debug.apk"
cp "$RELEASE_APK" "$DIST/PartFlasher-${VERSION}-arm64-release.apk"

for apk in "$DIST"/*.apk; do
  echo "== $apk"
  "$ZIPALIGN" -c -P 16 -v 4 "$apk" >/dev/null
  "$APKSIGNER" verify --verbose "$apk" >/dev/null
  "$AAPT2" dump badging "$apk" | grep -E "package:|sdkVersion|targetSdkVersion|native-code|launchable-activity" || true
  unzip -l "$apk" | grep -E "lib/.*/libpartflasher.so" || true
  sha256sum "$apk"
done

cat > "$DIST/SHA256SUMS.txt" <<SUMS
$(cd "$DIST" && sha256sum *.apk)
SUMS
echo "Deliverables in $DIST"
