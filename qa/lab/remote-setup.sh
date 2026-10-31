#!/system/bin/sh
# PartFlasher QA fake-root lab: guest-side setup (run as root via adb).
#
# Creates a world-writable staging dir plus loop-backed fake partitions under
# /dev/block/by-name so the app (running unprivileged, through fake su) can
# read/backup/write them. Prints a machine-readable state file at
# /data/local/tmp/partflasher/lab-state.sh.
#
# Idempotent: re-running reuses existing backing files/loop devices/symlinks.
set -u

STAGE=/data/local/tmp/partflasher
BYNAME=/dev/block/by-name
STATE="$STAGE/lab-state.sh"
TAG=partflasher-lab

log() { echo "== $*"; }
warn() { echo "WARN: $*" >&2; }
die() { echo "FAIL: $*" >&2; exit 1; }

# --- SELinux ---------------------------------------------------------------
log "SELinux before: $(getenforce 2>/dev/null || echo unknown)"
setenforce 0 2>/dev/null || true
SELINUX_NOW="$(getenforce 2>/dev/null || echo unknown)"
log "SELinux after : $SELINUX_NOW"
if [ "$SELINUX_NOW" = "Enforcing" ]; then
  warn "SELinux still enforcing; unprivileged app writes to /data/local/tmp may be denied."
fi

# --- directories -----------------------------------------------------------
mkdir -p "$STAGE" "$BYNAME" || die "cannot create $STAGE / $BYNAME"
chmod 777 /data/local/tmp
chmod 777 "$STAGE"
chmod 755 "$BYNAME"
log "staging dir: $(ls -ld "$STAGE")"
log "by-name dir: $(ls -ld "$BYNAME")"

have_blockdev() { command -v blockdev >/dev/null 2>&1; }

# --- loop helpers ----------------------------------------------------------
loop_backing() { # $1 = /dev/block/loopN -> prints backing file (empty if free)
  local n="${1##*/loop}"
  cat "/sys/block/loop$n/loop/backing_file" 2>/dev/null
}

free_loop() {
  local n d bf
  # APEX images occupy many low-numbered loop devices; ask the kernel first.
  d="$(losetup -f 2>/dev/null)"
  if [ -n "$d" ] && [ -b "$d" ]; then
    echo "$d"
    return 0
  fi
  for n in 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 \
           36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 63; do
    d="/dev/block/loop$n"
    [ -b "$d" ] || continue
    bf="$(loop_backing "$d")"
    if [ -z "$bf" ]; then
      echo "$d"
      return 0
    fi
  done
  return 1
}

ensure_loop() { # $1 = backing file, $2 = size MiB -> prints loop device path
  local f="$1" mib="$2" n d bf
  if [ ! -f "$f" ]; then
    log "creating backing file $f (${mib} MiB)" >&2
    dd if=/dev/zero of="$f" bs=1M count="$mib" 2>/dev/null || return 1
  fi
  # Reuse an existing attachment for this exact file.
  for n in 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 \
           36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 63; do
    [ -b "/dev/block/loop$n" ] || continue
    bf="$(loop_backing "/dev/block/loop$n")"
    if [ "$bf" = "$f" ]; then
      echo "/dev/block/loop$n"
      return 0
    fi
  done
  d="$(free_loop)" || { warn "no free loop device for $f"; return 1; }
  losetup "$d" "$f" || { warn "losetup $d $f failed"; return 1; }
  echo "$d"
}

# --- fake partitions -------------------------------------------------------
LOOP_A="$(ensure_loop "$STAGE/lab_disk_a.img" 128)" || die "cannot set up test_a backing loop"
LOOP_B="$(ensure_loop "$STAGE/lab_disk_b.img" 128)" || die "cannot set up test_b backing loop"
[ -n "$LOOP_A" ] && [ -b "$LOOP_A" ] || die "test_a loop device missing"
[ -n "$LOOP_B" ] && [ -b "$LOOP_B" ] || die "test_b loop device missing"
chmod 666 "$LOOP_A" "$LOOP_B" || die "cannot chmod loop devices"
log "test_a loop: $LOOP_A backing=$(loop_backing "$LOOP_A")"
log "test_b loop: $LOOP_B backing=$(loop_backing "$LOOP_B")"

# link_part NAME LOOP: creates by-name symlink only if nothing real is there.
# NOTE: mksh (Android /system/bin/sh) expands `local` assignments before binding
# them, so keep declarations and assignments separate (set -u would abort).
link_part() {
  local name loop link cur
  name="$1"
  loop="$2"
  link="$BYNAME/$name"
  if [ -L "$link" ]; then
    cur="$(readlink "$link" 2>/dev/null)"
    if [ "$cur" = "$loop" ]; then
      log "  keep $name -> $loop"
      return 0
    fi
    warn "  $name already links to $cur; leaving it alone"
    return 1
  fi
  if [ -e "$link" ]; then
    warn "  $name is a real block device; leaving it alone"
    return 1
  fi
  ln -s "$loop" "$link" || { warn "  ln -s $loop $link failed"; return 1; }
  log "  linked $name -> $loop"
}

LINKED_A=0
LINKED_B=0
link_part test_a "$LOOP_A" && LINKED_A=1
link_part test_b "$LOOP_B" && LINKED_B=1

# --- protected partition fixture ------------------------------------------
# The app blocks exact names: userdata, metadata, persist, frp, devinfo,
# keystore, misc, ssd, modemst1/modemst2, fsg, efs. Re-use an existing lab
# fixture if present (so re-running setup does not create a second one), then
# fall back to a name that does not exist on this emulator.
CANDIDATES="metadata persist frp devinfo misc fsg efs modemst1 ssd keystore"
PROTECTED_NAME=""
for cand in $CANDIDATES; do
  link="$BYNAME/$cand"
  [ -L "$link" ] || continue
  tgt="$(readlink "$link" 2>/dev/null)"
  case "$tgt" in
    /dev/block/loop*)
      bf="$(cat "/sys/block/${tgt##*/}/loop/backing_file" 2>/dev/null)"
      case "$bf" in
        "$STAGE"/lab_protected_*)
          PROTECTED_NAME="$cand"
          log "reusing existing protected fixture $cand -> $tgt"
          break
          ;;
      esac
      ;;
  esac
done
if [ -z "$PROTECTED_NAME" ]; then
  for cand in $CANDIDATES; do
    if [ ! -e "$BYNAME/$cand" ] && [ ! -L "$BYNAME/$cand" ]; then
      PROTECTED_NAME="$cand"
      break
    fi
  done
fi
PROTECTED_LOOP=""
if [ -n "$PROTECTED_NAME" ]; then
  PROTECTED_LOOP="$(ensure_loop "$STAGE/lab_protected_${PROTECTED_NAME}.img" 64)" || true
  if [ -n "$PROTECTED_LOOP" ] && [ -b "$PROTECTED_LOOP" ]; then
    chmod 666 "$PROTECTED_LOOP"
    if link_part "$PROTECTED_NAME" "$PROTECTED_LOOP"; then
      log "protected fixture: $PROTECTED_NAME -> $PROTECTED_LOOP"
    else
      PROTECTED_LOOP=""
    fi
  else
    warn "could not create protected partition fixture"
    PROTECTED_LOOP=""
  fi
else
  warn "no free protected partition name available on this image"
fi

# --- misc fixtures ---------------------------------------------------------
# A second fake boot-family partition that should look like a normal target.
LOOP_DTBO="$(ensure_loop "$STAGE/lab_disk_dtbo.img" 16)" || true
if [ -n "$LOOP_DTBO" ] && [ -b "$LOOP_DTBO" ]; then
  chmod 666 "$LOOP_DTBO"
  link_part dtbo_qa "$LOOP_DTBO" || true
fi

# --- state file ------------------------------------------------------------
{
  echo "# generated by qa/lab/remote-setup.sh"
  echo "LAB_SELINUX=$SELINUX_NOW"
  echo "LAB_LOOP_A=$LOOP_A"
  echo "LAB_LOOP_B=$LOOP_B"
  echo "LAB_LINKED_A=$LINKED_A"
  echo "LAB_LINKED_B=$LINKED_B"
  echo "LAB_PROTECTED_NAME=$PROTECTED_NAME"
  echo "LAB_PROTECTED_LOOP=$PROTECTED_LOOP"
  echo "LAB_DISK_A=$STAGE/lab_disk_a.img"
  echo "LAB_DISK_B=$STAGE/lab_disk_b.img"
} > "$STATE"
chmod 666 "$STATE"
log "state file: $STATE"

# --- verification output ---------------------------------------------------
log "by-name listing:"
ls -l "$BYNAME"
for pair in "test_a:$LOOP_A" "test_b:$LOOP_B" "protected:$PROTECTED_LOOP"; do
  name="${pair%%:*}"
  dev="${pair#*:}"
  [ -n "$dev" ] || continue
  if have_blockdev; then
    log "  $name device $dev size=$(blockdev --getsize64 "$dev" 2>/dev/null || echo '?') bytes perms=$(ls -l "$dev" 2>/dev/null)"
  else
    log "  $name device $dev perms=$(ls -l "$dev" 2>/dev/null)"
  fi
done
log "partflasher staging dir listing:"
ls -la "$STAGE"

echo "LAB SETUP OK"
