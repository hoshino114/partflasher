#!/usr/bin/env bash
# Start the PartFlasher QA emulator headlessly. Idempotent: does nothing if the
# AVD is already running/booting.
#
# Usage: qa/start-emulator.sh
# Logs : qa/artifacts/emulator.log   PID: qa/artifacts/emulator.pid
set -euo pipefail
# shellcheck source=env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

echo "== PartFlasher QA emulator =="
echo "SDK        : $ANDROID_HOME"
echo "AVD        : $AVD_NAME"
echo "Serial     : $ANDROID_SERIAL (port $EMULATOR_PORT)"
echo "Log        : $EMU_LOG"

if [[ ! -x "$EMULATOR_BIN" ]]; then
  echo "ERROR: emulator binary missing: $EMULATOR_BIN" >&2
  echo "       Run .tooling/qa/install-sdk.sh first." >&2
  exit 1
fi

# --- KVM access -------------------------------------------------------------
if [[ -e /dev/kvm ]] && { [[ ! -r /dev/kvm ]] || [[ ! -w /dev/kvm ]]; }; then
  echo "Granting /dev/kvm access to $(id -un) via sudo ..."
  if sudo -n chmod 666 /dev/kvm 2>/dev/null; then
    echo "OK: /dev/kvm is now $(stat -c '%A %U:%G' /dev/kvm)"
  else
    echo "WARN: could not chmod /dev/kvm (passwordless sudo failed)." >&2
    echo "      Emulator may fall back to software emulation." >&2
  fi
fi

# --- Already running? -------------------------------------------------------
if [[ -f "$EMU_PID_FILE" ]]; then
  OLD_PID="$(cat "$EMU_PID_FILE" 2>/dev/null || true)"
  if [[ -n "$OLD_PID" ]] && kill -0 "$OLD_PID" 2>/dev/null; then
    echo "Emulator process already running (pid $OLD_PID); nothing to do."
    echo "Run qa/wait-emulator.sh to wait for boot."
    exit 0
  fi
  rm -f "$EMU_PID_FILE"
fi
if "$ADB" devices 2>/dev/null | awk -v s="$ANDROID_SERIAL" '$1==s && $2=="device"{found=1} END{exit !found}'; then
  echo "ADB already reports $ANDROID_SERIAL as device; nothing to do."
  exit 0
fi

# --- Ensure AVD exists ------------------------------------------------------
avd_exists() {
  "$AVD_MANAGER" list avd 2>/dev/null | grep -qE "Name: ${AVD_NAME}$"
}

create_avd_manual() {
  # Fallback when avdmanager cannot run (e.g. license/network issues). Uses the
  # generic API-36 system image ABI dir and a Pixel-6-like hardware profile.
  echo "Falling back to manual AVD creation under ~/.android/avd ..."
  local base="$HOME/.android/avd/${AVD_NAME}.avd"
  mkdir -p "$base"
  cat > "$HOME/.android/avd/${AVD_NAME}.ini" <<EOF
avd.ini.encoding=UTF-8
path=$base
path.rel=avd/${AVD_NAME}.avd
target=android-36
EOF
  cat > "$base/config.ini" <<EOF
AvdId=${AVD_NAME}
PlayStore.enabled=false
abi.type=x86_64
avd.ini.displayname=PartFlasher API 36
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.forceColdBoot=yes
hw.accelerometer=yes
hw.audioInput=no
hw.audioOutput=no
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.dPad=no
hw.device.manufacturer=Google
hw.device.name=pixel_6
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.initialOrientation=Portrait
hw.keyboard=yes
hw.lcd.density=440
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=4096
hw.sdCard=no
hw.sensors.orientation=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=system-images/android-36/google_apis/x86_64/
showDeviceFrame=no
tag.display=Google APIs
tag.id=google_apis
vm.heapSize=512
EOF
  echo "Manual AVD created at $base"
}

if avd_exists; then
  echo "AVD $AVD_NAME already exists."
else
  echo "Creating AVD $AVD_NAME from $SYS_IMAGE ..."
  set +e
  printf 'no\n' | "$AVD_MANAGER" create avd -n "$AVD_NAME" -k "$SYS_IMAGE" \
      -d pixel_6 --force
  rc=$?
  set -e
  if [[ $rc -ne 0 ]] || ! avd_exists; then
    echo "WARN: avdmanager failed (rc=$rc); trying manual AVD config." >&2
    create_avd_manual
  fi
fi

# --- Launch -----------------------------------------------------------------
: > "$EMU_LOG"
echo "Launching emulator ..."
# shellcheck disable=SC2086
nohup "$EMULATOR_BIN" \
  -avd "$AVD_NAME" \
  -port "$EMULATOR_PORT" \
  -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim \
  -no-snapshot -no-metrics -accel on -writable-system \
  >"$EMU_LOG" 2>&1 &
EMU_PID=$!
echo "$EMU_PID" > "$EMU_PID_FILE"
disown "$EMU_PID" 2>/dev/null || true

sleep 2
if ! kill -0 "$EMU_PID" 2>/dev/null; then
  echo "ERROR: emulator exited immediately. Log tail:" >&2
  tail -n 40 "$EMU_LOG" >&2
  rm -f "$EMU_PID_FILE"
  exit 1
fi
echo "Started emulator pid $EMU_PID. Run qa/wait-emulator.sh to wait for boot."
