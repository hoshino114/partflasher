#!/usr/bin/env bash
# Wait until the PartFlasher QA emulator has fully booted, then verify that it
# is the expected API 36 x86_64 image and write qa/artifacts/emulator-boot.txt.
#
# Usage: qa/wait-emulator.sh [timeout_seconds]   (default 600)
set -euo pipefail
# shellcheck source=env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

TIMEOUT="${1:-${BOOT_TIMEOUT:-600}}"
BOOT_REPORT="$QA_ART/emulator-boot.txt"

adb_shell() {
  "$ADB" -s "$ANDROID_SERIAL" shell "$@" 2>/dev/null | tr -d '\r'
}

echo "Waiting for $ANDROID_SERIAL (timeout ${TIMEOUT}s) ..."
"$ADB" start-server >/dev/null

# adb wait-for-device has no timeout of its own; bound it with `timeout`.
if ! timeout "$TIMEOUT" env ANDROID_SERIAL="$ANDROID_SERIAL" "$ADB" wait-for-device; then
  echo "ERROR: device $ANDROID_SERIAL did not appear within ${TIMEOUT}s." >&2
  echo "--- emulator.log tail ---" >&2
  tail -n 60 "$EMU_LOG" 2>/dev/null >&2 || true
  exit 1
fi
echo "Device connected; waiting for sys.boot_completed=1 ..."

DEADLINE=$((SECONDS + TIMEOUT))
while :; do
  BOOTED="$(adb_shell getprop sys.boot_completed || true)"
  if [[ "$BOOTED" == "1" ]]; then
    break
  fi
  if (( SECONDS >= DEADLINE )); then
    echo "ERROR: boot did not complete within ${TIMEOUT}s (sys.boot_completed='$BOOTED')." >&2
    tail -n 60 "$EMU_LOG" >&2 || true
    exit 1
  fi
  sleep 2
done

# Give the framework a few seconds to settle after boot_completed (SystemUI,
# package manager, etc).
sleep 5

SDK="$(adb_shell getprop ro.build.version.sdk)"
ABI="$(adb_shell getprop ro.product.cpu.abi)"
ABILIST="$(adb_shell getprop ro.product.cpu.abilist)"
RELEASE="$(adb_shell getprop ro.build.version.release)"
MODEL="$(adb_shell getprop ro.product.model)"
FINGERPRINT="$(adb_shell getprop ro.build.fingerprint)"
UNAME="$(adb_shell uname -a)"

# Unlock / dismiss keyguard and skip the first-run setup wizard if present.
adb_shell input keyevent 82 >/dev/null 2>&1 || true
adb_shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb_shell settings put secure user_setup_complete 1 >/dev/null 2>&1 || true
adb_shell settings put global device_provisioned 1 >/dev/null 2>&1 || true
adb_shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || true
adb_shell svc power stayon true >/dev/null 2>&1 || true
adb_shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb_shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb_shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true

{
  date '+%Y-%m-%dT%H:%M:%S%z'
  echo "serial=$ANDROID_SERIAL"
  echo "avd=$AVD_NAME"
  echo "ro.build.version.sdk=$SDK"
  echo "ro.build.version.release=$RELEASE"
  echo "ro.product.cpu.abi=$ABI"
  echo "ro.product.cpu.abilist=$ABILIST"
  echo "ro.product.model=$MODEL"
  echo "ro.build.fingerprint=$FINGERPRINT"
  echo "uname=$UNAME"
  echo "boot_completed=1"
  echo "--- adb devices ---"
  "$ADB" devices
} | tee "$BOOT_REPORT"

status=0
[[ "$SDK" == "36" ]] || { echo "WARN: unexpected sdk '$SDK' (expected 36)" >&2; status=1; }
[[ "$ABI" == "x86_64" ]] || { echo "WARN: unexpected abi '$ABI' (expected x86_64)" >&2; status=1; }
if [[ $status -ne 0 ]]; then
  echo "Emulator booted but did not match expected API 36 / x86_64." >&2
  exit 1
fi
echo "OK: emulator $ANDROID_SERIAL booted as API $SDK $ABI ($RELEASE)."
echo "Boot report: $BOOT_REPORT"
