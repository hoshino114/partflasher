#!/usr/bin/env bash
# Watchdog: keep the QA emulator booted. Restarts a dead emulator and re-runs
# the boot wait; exits if the emulator is already running and healthy.
# Intended to be launched as a managed background job during long QA runs.
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

INTERVAL="${KEEPALIVE_INTERVAL:-30}"
echo "[keepalive] watching $ANDROID_SERIAL every ${INTERVAL}s (pid $$)"
while true; do
  if "$ADB" devices 2>/dev/null | awk -v s="$ANDROID_SERIAL" '$1==s && $2=="device"{found=1} END{exit !found}'; then
    BOOTED="$("$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "$BOOTED" != "1" ]]; then
      echo "[keepalive] $(date '+%T') device present but not booted; waiting"
    fi
  else
    echo "[keepalive] $(date '+%T') device missing/offline; restarting emulator"
    "$QA_ROOT/qa/start-emulator.sh" || echo "[keepalive] start failed"
    "$QA_ROOT/qa/wait-emulator.sh" 600 || echo "[keepalive] wait failed"
  fi
  sleep "$INTERVAL"
done
