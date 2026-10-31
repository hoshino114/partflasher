#!/usr/bin/env bash
# Reproducible local build environment for PartFlasher.
#
#   bash scripts/setup-env.sh            # JDK check + SDK + Gradle (no NDK)
#   bash scripts/setup-env.sh --with-ndk # also download NDK r28c for native rebuilds
#
# Assets come from the Tencent Android SDK mirror because dl.google.com is not
# reachable from some networks. Everything is installed under .tooling/ and
# .android-sdk/ (git-ignored); no root is needed except for the optional JDK
# install hint.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOWNLOADS="$ROOT/.tooling/downloads"
SDK="$ROOT/.android-sdk"
GRADLE_DIR="$ROOT/.tooling/gradle-dist"
WITH_NDK=0
[[ "${1:-}" == "--with-ndk" ]] && WITH_NDK=1
mkdir -p "$DOWNLOADS" "$SDK/licenses"

TENCENT="https://mirrors.cloud.tencent.com/AndroidSDK"
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.13-bin.zip"

sha1_of() { sha1sum "$1" | cut -d' ' -f1; }
need_download() {
  local file="$1" url="$2" want_sha1="$3"
  if [[ -f "$file" ]] && [[ "$(sha1_of "$file")" == "$want_sha1" ]]; then
    echo "ok: $(basename "$file") (cached)"
    return 1
  fi
  echo "downloading $(basename "$file") ..."
  curl -fL --retry 3 --connect-timeout 20 -o "$file.part" "$url"
  if [[ "$(sha1_of "$file.part")" != "$want_sha1" ]]; then
    echo "ERROR: checksum mismatch for $file" >&2
    rm -f "$file.part"; exit 1
  fi
  mv "$file.part" "$file"
  return 0
}

# --- JDK 17 ---------------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
  echo "JDK 17 not found. Install it first, e.g.: sudo apt-get install -y openjdk-17-jdk-headless" >&2
  exit 1
fi
JAVA_MAJOR="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 17 ]]; then
  echo "JDK 17+ required (found ${JAVA_MAJOR:-unknown})." >&2
  exit 1
fi

# --- Android SDK packages -------------------------------------------------
need_download "$DOWNLOADS/commandlinetools.zip" \
  "$TENCENT/commandlinetools-linux-16111833_latest.zip" e025545c62a8e64c7559119566a569fb1dec5f60 || true
need_download "$DOWNLOADS/platform-36.zip" \
  "$TENCENT/platform-36-ext19_r01.zip" 52e240c229cf2ad561ee262ebdfff266e180c585 || true
need_download "$DOWNLOADS/build-tools-36.zip" \
  "$TENCENT/build-tools_r36_linux.zip" b0b6376977657e8ad9b969bacf4093601da2c6fb || true
need_download "$DOWNLOADS/platform-tools.zip" \
  "$TENCENT/platform-tools_r37.0.1-linux.zip" 477254aa5f903c15cf51001717bdf347fb6b53e0 || true
if [[ "$WITH_NDK" == 1 ]]; then
  need_download "$DOWNLOADS/android-ndk-r28c-linux.zip" \
    "$TENCENT/android-ndk-r28c-linux.zip" a7b54a5de87fecd125a17d54f73c446199e72a64 || true
fi

if [[ ! -d "$SDK/platforms/android-36" ]]; then
  tmp="$(mktemp -d)"
  unzip -q "$DOWNLOADS/platform-36.zip" -d "$tmp"
  mkdir -p "$SDK/platforms"
  mv "$tmp"/android-36-ext19 "$SDK/platforms/android-36"
  # The Android 16 extension package ships a localPackage path that AGP does
  # not map to platforms/android-36; normalize it for local installs.
  sed -i 's#platforms;android-36-ext19#platforms;android-36#g' "$SDK/platforms/android-36/package.xml"
  rm -rf "$tmp"
fi
if [[ ! -d "$SDK/build-tools/36.0.0" ]]; then
  tmp="$(mktemp -d)"
  unzip -q "$DOWNLOADS/build-tools-36.zip" -d "$tmp"
  mkdir -p "$SDK/build-tools"
  mv "$tmp"/android-16 "$SDK/build-tools/36.0.0"
  rm -rf "$tmp"
fi
if [[ ! -d "$SDK/platform-tools" ]]; then
  unzip -q "$DOWNLOADS/platform-tools.zip" -d "$SDK"
fi
if [[ ! -d "$SDK/cmdline-tools/latest" ]]; then
  tmp="$(mktemp -d)"
  unzip -q "$DOWNLOADS/commandlinetools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
chmod +x "$SDK"/build-tools/36.0.0/* "$SDK"/platform-tools/* "$SDK"/cmdline-tools/latest/bin/* 2>/dev/null || true
# Local license acceptance (AGP checks these files, no remote sdkmanager needed).
for license in android-sdk-license android-sdk-preview-license; do
  cat > "$SDK/licenses/$license" <<'LIC'
24333f8a63b6825ea9c5514f83c2829b004d1fee
d56f5187479451eabf01fb78af6dfcb131a6481e
8933bad161af4178b1185d1a37fbf41ea5269c55
84831b9409646a918e30573bab4c9c91346d8abd
LIC
done

if [[ "$WITH_NDK" == 1 && ! -d "$ROOT/.tooling/ndk/android-ndk-r28c" ]]; then
  mkdir -p "$ROOT/.tooling/ndk"
  unzip -q "$DOWNLOADS/android-ndk-r28c-linux.zip" -d "$ROOT/.tooling/ndk"
fi

# --- Gradle 8.13 ----------------------------------------------------------
if [[ ! -x "$GRADLE_DIR/gradle-8.13/bin/gradle" ]]; then
  if [[ ! -f "$DOWNLOADS/gradle-8.13-bin.zip" ]] || \
     [[ "$(sha256sum "$DOWNLOADS/gradle-8.13-bin.zip" | cut -d' ' -f1)" != \
        "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78" ]]; then
    echo "downloading Gradle 8.13 ..."
    curl -fL --retry 3 -o "$DOWNLOADS/gradle-8.13-bin.zip.part" "$GRADLE_URL"
    echo "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78  $DOWNLOADS/gradle-8.13-bin.zip.part" | sha256sum -c -
    mv "$DOWNLOADS/gradle-8.13-bin.zip.part" "$DOWNLOADS/gradle-8.13-bin.zip"
  fi
  mkdir -p "$GRADLE_DIR"
  unzip -q "$DOWNLOADS/gradle-8.13-bin.zip" -d "$GRADLE_DIR"
fi

cat > "$ROOT/local.properties" <<LOCAL
sdk.dir=$SDK
LOCAL
cat > "$ROOT/.tooling/dev-env.sh" <<ENV
PROJECT_ROOT="$ROOT"
export PROJECT_ROOT
# Prefer a working JAVA_HOME, otherwise derive it from the JDK on PATH.
if [[ -z "\${JAVA_HOME:-}" || ! -x "\$JAVA_HOME/bin/java" ]]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_HOME="\$(dirname "\$(dirname "\$(readlink -f "\$(command -v java)")")")"
  else
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  fi
fi
export JAVA_HOME
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/build-tools/36.0.0:\$PATH"
ENV
echo
echo "Environment ready."
echo "  source .tooling/dev-env.sh"
echo "  ./scripts/gradle.sh :app:assembleDebug"
echo "  (optional) bash native/build.sh   # rebuild arm64 JNI libs"
