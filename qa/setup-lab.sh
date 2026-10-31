#!/usr/bin/env bash
# Build the fake-root flashing lab on the running emulator. Idempotent.
#
# What it does:
#   1. adb root + permissive SELinux (google_apis is userdebug)
#   2. remount /system writable and install fake /system/bin/su + /system/bin/magisk
#   3. create /data/adb/magisk so root detection sees Magisk
#   4. create 128 MiB loop-backed test_a / test_b (+ a protected-name fixture)
#      under /dev/block/by-name and a world-writable /data/local/tmp/partflasher
#   5. write qa/artifacts/lab-state.env and qa/artifacts/lab-setup.txt
#
# The fake su executes commands with the app's own uid, so all lab targets are
# world-writable and SELinux is permissive. This is a QA lab only.
set -euo pipefail
# shellcheck source=env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

SETUP_LOG="$QA_ART/lab-setup.log"
SETUP_TXT="$QA_ART/lab-setup.txt"
STATE_OUT="$QA_ART/lab-state.env"
: > "$SETUP_LOG"
exec > >(tee -a "$SETUP_LOG") 2>&1

step() { printf '\n===== %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
shell() { "$ADB" -s "$ANDROID_SERIAL" shell "$@"; }
host_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

step "0. device / root"
"$ADB" -s "$ANDROID_SERIAL" wait-for-device
"$ADB" -s "$ANDROID_SERIAL" root >/dev/null 2>&1 || true
"$ADB" -s "$ANDROID_SERIAL" wait-for-device
ADB_UID="$(shell id | tr -d '\r')"
echo "adb shell identity: $ADB_UID"
case "$ADB_UID" in
  *uid=0*) ;;
  *) die "adb root failed; emulator is not a userdebug image (expected google_apis)" ;;
esac

step "1. SELinux permissive"
shell setenforce 0 >/dev/null 2>&1 || true
SELINUX="$(shell getenforce | tr -d '\r')"
echo "getenforce: $SELINUX"
[[ "$SELINUX" == "Permissive" ]] || echo "WARN: SELinux is $SELINUX; app writes may be denied"

step "2. make /system writable"
system_writable() {
  shell 'touch /system/bin/.qa_rwtest && rm -f /system/bin/.qa_rwtest' >/dev/null 2>&1
}
"$ADB" -s "$ANDROID_SERIAL" remount 2>&1 || true
if ! system_writable; then
  echo "adb remount did not help; trying explicit rw remounts"
  shell 'mount -o rw,remount /' >/dev/null 2>&1 || true
  shell 'mount -o rw,remount /system' >/dev/null 2>&1 || true
fi
if ! system_writable; then
  echo "Still read-only; disabling verity and rebooting (one-time AVD setup)"
  "$ADB" -s "$ANDROID_SERIAL" disable-verity 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" reboot 2>&1 || true
  sleep 5
  "$host_script_dir/wait-emulator.sh" "${BOOT_TIMEOUT:-600}" || die "emulator did not come back after disable-verity reboot"
  "$ADB" -s "$ANDROID_SERIAL" root >/dev/null 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" wait-for-device
  shell setenforce 0 >/dev/null 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" remount 2>&1 || true
fi
system_writable || die "cannot make /system writable"
echo "/system is writable"

step "3. install fake root manager (su + magisk)"
"$ADB" -s "$ANDROID_SERIAL" push "$host_script_dir/lab/su" /system/bin/su >/dev/null
"$ADB" -s "$ANDROID_SERIAL" push "$host_script_dir/lab/magisk" /system/bin/magisk >/dev/null
shell 'chmod 755 /system/bin/su /system/bin/magisk'
shell 'mkdir -p /system/xbin && cp /system/bin/su /system/xbin/su && chmod 755 /system/xbin/su'
# /system/bin/su must be visible to the app's PATH lookup.
echo "installed: $(shell 'ls -l /system/bin/su /system/bin/magisk /system/xbin/su' | tr '\r\n' ' ')"

step "4. fake Magisk data tree"
shell 'mkdir -p /data/adb/magisk /data/adb/modules && chmod 700 /data/adb/magisk'
"$ADB" -s "$ANDROID_SERIAL" push "$host_script_dir/lab/magisk" /data/adb/magisk/magisk >/dev/null
shell 'chmod 755 /data/adb/magisk/magisk'
shell 'printf "MAGISK_VER=27.0\nMAGISK_VER_CODE=27000\n" > /data/adb/magisk/util_functions.sh && chmod 644 /data/adb/magisk/util_functions.sh'
shell 'touch /data/adb/magisk.db /data/adb/.partflasher_qa_lab'
echo "magisk tree:"
shell 'ls -la /data/adb | tr -d "\r"'

step "5. guest-side loop-backed partitions"
"$ADB" -s "$ANDROID_SERIAL" push "$host_script_dir/lab/remote-setup.sh" /data/local/tmp/qa-remote-setup.sh >/dev/null
shell 'chmod 755 /data/local/tmp/qa-remote-setup.sh'
if ! shell 'sh /data/local/tmp/qa-remote-setup.sh'; then
  die "remote lab setup failed (see output above)"
fi

step "6. pull lab state"
shell 'cat /data/local/tmp/partflasher/lab-state.sh' | tr -d '\r' > "$STATE_OUT" || true
cat "$STATE_OUT"
# shellcheck disable=SC1090
source "$STATE_OUT"
echo
echo "resolved: test_a=${LAB_LOOP_A:-?} test_b=${LAB_LOOP_B:-?} protected=${LAB_PROTECTED_NAME:-none}"

step "7. verify fake su interface and partition devices"
echo "su -v        : $(shell '/system/bin/su -v' | tr -d '\r')"
echo "su --version : $(shell '/system/bin/su --version' | tr -d '\r')"
echo "su -c id     : $(shell "/system/bin/su -c 'id'" | tr -d '\r')"
echo "su -c echo   : $(shell "/system/bin/su -c 'echo fake-root-works'" | tr -d '\r')"
echo "by-name      :"
shell 'ls -l /dev/block/by-name' | tr -d '\r'
echo "sizes        :"
for pair in "test_a:${LAB_LOOP_A:-}" "test_b:${LAB_LOOP_B:-}" "${LAB_PROTECTED_NAME:-none}:${LAB_PROTECTED_LOOP:-}"; do
  name="${pair%%:*}"; dev="${pair#*:}"
  [[ -n "$dev" ]] || continue
  echo "  $name $(shell "blockdev --getsize64 '$dev'" | tr -d '\r') bytes ($dev)"
done
echo "staging dir  : $(shell 'ls -ld /data/local/tmp/partflasher' | tr -d '\r')"
echo "staging perms: $(shell 'stat -c "%A %U:%G" /data/local/tmp /data/local/tmp/partflasher' | tr -d '\r\n')"

step "8. write artifacts"
{
  echo "PartFlasher QA fake-root lab"
  date '+%Y-%m-%dT%H:%M:%S%z'
  echo "serial:    $ANDROID_SERIAL"
  echo "adb uid:   $ADB_UID"
  echo "selinux:   $SELINUX"
  echo "fake su:   /system/bin/su (version 27.0:MAGISK)"
  echo "test_a:    ${LAB_LINKED_A:+$LAB_LOOP_A}"
  echo "test_b:    ${LAB_LINKED_B:+$LAB_LOOP_B}"
  echo "protected: ${LAB_PROTECTED_NAME:-none} -> ${LAB_PROTECTED_LOOP:-none}"
  echo "staging:   /data/local/tmp/partflasher (0777)"
  echo
  echo "--- by-name ---"
  shell 'ls -l /dev/block/by-name' | tr -d '\r'
} > "$SETUP_TXT"

echo
echo "LAB READY. State: $STATE_OUT  Summary: $SETUP_TXT"
