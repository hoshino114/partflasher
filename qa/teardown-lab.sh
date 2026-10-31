#!/usr/bin/env bash
# Tear down the fake-root lab created by qa/setup-lab.sh. Idempotent.
# Removes only lab fixtures (loop devices, by-name symlinks pointing at loop
# devices, staging files, fake su/magisk) and restores SELinux enforcing.
set -euo pipefail
# shellcheck source=env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

TEARDOWN_LOG="$QA_ART/lab-teardown.log"
: > "$TEARDOWN_LOG"
exec > >(tee -a "$TEARDOWN_LOG") 2>&1

step() { printf '\n===== %s\n' "$*"; }
shell() { "$ADB" -s "$ANDROID_SERIAL" shell "$@"; }
host_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

step "0. connect"
if ! "$ADB" -s "$ANDROID_SERIAL" get-state >/dev/null 2>&1; then
  echo "No device $ANDROID_SERIAL; nothing to tear down."
  exit 0
fi
"$ADB" -s "$ANDROID_SERIAL" root >/dev/null 2>&1 || true
"$ADB" -s "$ANDROID_SERIAL" wait-for-device
shell 'id' | grep -q 'uid=0' || echo "WARN: adb shell is not root; teardown may be incomplete"

step "1. guest-side teardown"
"$ADB" -s "$ANDROID_SERIAL" push "$host_script_dir/lab/remote-teardown.sh" /data/local/tmp/qa-remote-teardown.sh >/dev/null
shell 'chmod 755 /data/local/tmp/qa-remote-teardown.sh'
shell 'sh /data/local/tmp/qa-remote-teardown.sh' || echo "WARN: remote teardown reported an error"
shell 'rm -f /data/local/tmp/qa-remote-teardown.sh /data/local/tmp/qa-remote-setup.sh'

step "2. verify"
echo "by-name:"
shell 'ls -l /dev/block/by-name' | tr -d '\r' || true
echo "su probe (expected: not found):"
shell 'command -v su || echo "(no su in PATH)"' | tr -d '\r'
echo "partflasher dir:"
shell 'ls -la /data/local/tmp/partflasher 2>/dev/null' | tr -d '\r' || true
echo "selinux: $(shell getenforce | tr -d '\r')"

rm -f "$QA_ART/lab-state.env"
echo
echo "LAB TORN DOWN."
