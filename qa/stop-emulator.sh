#!/usr/bin/env bash
# Stop the PartFlasher QA emulator. Idempotent.
#
# Usage: qa/stop-emulator.sh
set -euo pipefail
# shellcheck source=env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

echo "Stopping emulator $ANDROID_SERIAL ..."
"$ADB" start-server >/dev/null 2>&1 || true

"$ADB" -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true

PID=""
[[ -f "$EMU_PID_FILE" ]] && PID="$(cat "$EMU_PID_FILE" 2>/dev/null || true)"

# Wait up to 60s for the process to exit.
for _ in $(seq 1 60); do
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    sleep 1
    continue
  fi
  if "$ADB" devices 2>/dev/null | awk -v s="$ANDROID_SERIAL" '$1==s{found=1} END{exit !found}'; then
    sleep 1
    continue
  fi
  break
done

# Last resort: terminate the recorded emulator process.
if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
  echo "Emulator still alive; sending SIGTERM to pid $PID."
  kill "$PID" 2>/dev/null || true
  for _ in $(seq 1 20); do
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
  done
  kill -0 "$PID" 2>/dev/null && kill -9 "$PID" 2>/dev/null || true
fi

rm -f "$EMU_PID_FILE"
echo "Emulator stopped."
