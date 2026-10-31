#!/system/bin/sh
# PartFlasher QA fake-root lab: guest-side teardown (run as root via adb).
# Removes only the fixtures this lab created (loop attachments, by-name
# symlinks pointing at our loop devices, staging files). Leaves real devices
# and real partitions untouched. Restores SELinux to enforcing.
set -u

STAGE=/data/local/tmp/partflasher
BYNAME=/dev/block/by-name
STATE="$STAGE/lab-state.sh"

log() { echo "== $*"; }
warn() { echo "WARN: $*" >&2; }

# Load our recorded state (if the setup was run).
if [ -f "$STATE" ]; then
  # shellcheck disable=SC1090
  . "$STATE"
fi

# --- remove by-name symlinks that point at our loop devices ----------------
remove_link() { # name
  local name link target
  name="$1"
  link="$BYNAME/$name"
  [ -L "$link" ] || return 0
  target="$(readlink "$link" 2>/dev/null)"
  case "$target" in
    /dev/block/loop[0-9]*) ;;
    *) warn "  $name -> $target is not a loop device; keeping it"; return 0 ;;
  esac
  rm -f "$link" && log "  removed by-name/$name (-> $target)"
}

for name in test_a test_b "${LAB_PROTECTED_NAME:-}" dtbo_qa; do
  [ -n "$name" ] && remove_link "$name"
done

# Also sweep any by-name symlink that points at one of our loop backing files
# (covers fixtures created by earlier setup runs whose names are not in state).
for link in "$BYNAME"/*; do
  [ -L "$link" ] || continue
  target="$(readlink "$link" 2>/dev/null)"
  case "$target" in
    /dev/block/loop[0-9]*) ;;
    *) continue ;;
  esac
  bf="$(cat "/sys/block/${target##*/}/loop/backing_file" 2>/dev/null)"
  case "$bf" in
    "$STAGE"/*)
      rm -f "$link" && log "  removed leftover lab link ${link##*/} (-> $target, $bf)"
      ;;
  esac
done

# --- detach our loop devices ----------------------------------------------
# Sweep every loop device backed by a file under $STAGE (covers leftovers from
# earlier setup runs), not just the ones recorded in the state file.
for sysloop in /sys/block/loop*/loop/backing_file; do
  [ -f "$sysloop" ] || continue
  bf="$(cat "$sysloop" 2>/dev/null)"
  case "$bf" in
    "$STAGE"/*)
      n="$(echo "$sysloop" | sed 's#/sys/block/loop\([0-9]*\)/.*#\1#')"
      dev="/dev/block/loop$n"
      losetup -d "$dev" 2>/dev/null && log "  detached $dev ($bf)"
      ;;
  esac
done

# --- staging files ---------------------------------------------------------
# Only delete files this lab created, never a user's staged image.
for f in "$STAGE"/lab_disk_a.img "$STAGE"/lab_disk_b.img "$STAGE"/lab_protected_*.img "$STAGE"/lab_disk_dtbo.img "$STATE"; do
  [ -e "$f" ] && rm -f "$f" && log "  removed $f"
done
# Leave the directory itself (the app may expect it to exist).

# --- fake root manager binaries -------------------------------------------
for f in /system/bin/su /system/xbin/su /system/bin/magisk; do
  if [ -f "$f" ] && grep -q "PartFlasher QA" "$f" 2>/dev/null; then
    rm -f "$f" && log "  removed fake $f"
  elif [ -f "$f" ]; then
    warn "  $f does not look like our fake (not removing)"
  fi
done
if [ -f /data/adb/.partflasher_qa_lab ]; then
  rm -rf /data/adb/magisk /data/adb/magisk.db /data/adb/.partflasher_qa_lab
  log "  removed /data/adb/magisk fake tree"
fi

# --- restore SELinux -------------------------------------------------------
setenforce 1 2>/dev/null || true
log "SELinux restored: $(getenforce 2>/dev/null || echo unknown)"

echo "LAB TEARDOWN OK"
