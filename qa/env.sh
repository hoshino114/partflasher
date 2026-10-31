#!/usr/bin/env bash
# Shared environment for the PartFlasher QA lab scripts.
# Source this file (do not execute it):  source qa/env.sh

QA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../.tooling/dev-env.sh
source "$QA_ROOT/.tooling/dev-env.sh"

export ANDROID_SDK_ROOT="$ANDROID_HOME"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
AVD_MANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
SYS_IMAGE="system-images;android-36;google_apis;x86_64"

AVD_NAME="${AVD_NAME:-partflasher_api36}"
EMULATOR_PORT="${EMULATOR_PORT:-5554}"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-$EMULATOR_PORT}"
QA_ART="$QA_ROOT/qa/artifacts"
QA_LOGS="$QA_ART"
QA_FIXTURES="$QA_ROOT/qa/fixtures"
EMU_LOG="$QA_ART/emulator.log"
EMU_PID_FILE="$QA_ART/emulator.pid"

# The app under test (debug build has the .debug suffix).
APP_PACKAGE="${APP_PACKAGE:-com.partflasher.app.debug}"
APP_ACTIVITY="${APP_ACTIVITY:-.MainActivity}"

# Fake-root lab constants.
LAB_DISK_IMAGE="/data/local/tmp/partflasher/lab_disk.img"
LAB_DISK_SIZE_MIB="${LAB_DISK_SIZE_MIB:-128}"
LAB_STAGING_DIR="/data/local/tmp/partflasher"
LAB_BY_NAME="/dev/block/by-name"

mkdir -p "$QA_ART" "$QA_FIXTURES"
