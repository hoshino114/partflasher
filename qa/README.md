# PartFlasher QA lab (API 36 emulator + fake root)

Everything in this directory is QA/tooling only; no app source is modified.
Owned by `qa-lab` (task-3). Write scope: `qa/**`, `.tooling/qa/**`.

## 1. Result at a glance

| Item | Value |
|---|---|
| Emulator serial | `emulator-5554` (AVD `partflasher_api36`, Pixel 6, headless) |
| OS image | `system-images;android-36;google_apis;x86_64` (rev 7, userdebug) |
| Verified props | `ro.build.version.sdk=36`, `ro.build.version.release=16`, `ro.product.cpu.abi=x86_64` |
| Emulator binary | `.android-sdk/emulator/emulator` 37.2.13 |
| Boot log | `qa/artifacts/emulator.log` |
| Boot report | `qa/artifacts/emulator-boot.txt` |
| Lab state | `qa/artifacts/lab-state.env` |
| Lab setup log | `qa/artifacts/lab-setup.log` |
| Lab verification | `qa/artifacts/lab-verify.txt` |
| Fixtures | `qa/fixtures/` (+ `SHA256SUMS`) |
| App under test | `com.partflasher.app.debug` / `com.partflasher.app.MainActivity` |

## 2. Toolchain bootstrap (once)

`dl.google.com` is blocked, so SDK archives come from the Tencent mirror and are
unpacked manually. The archives are listed in the repository index
(`repository2-1.xml` for the emulator, `sys-img/google_apis/sys-img2-1.xml` for
the system image).

```bash
.tooling/qa/download-sdk.sh    # mirrors -> .tooling/qa/downloads (sha1-verified)
.tooling/qa/install-sdk.sh     # unpack -> .android-sdk, fix/synthesise package.xml
```

Notes / gotchas:
- The emulator zip has no `package.xml`; `install-sdk.sh` synthesises one with
  `localPackage path="emulator"`.
- The system-image zip also has no `package.xml`; a synthetic
  `sysImgDetailsType` descriptor with
  `localPackage path="system-images;android-36;google_apis;x86_64"` is written.
  A `<license id="android-sdk-license"/>` element must be present or avdmanager
  warns about an undefined license ID.
- `avdmanager create avd` does not accept `--sdk_root` after the subcommand; the
  `ANDROID_SDK_ROOT`/`ANDROID_HOME` env vars are enough.
- The AVD is created with
  `avdmanager create avd -n partflasher_api36 -k "system-images;android-36;google_apis;x86_64" -d pixel_6`.
  `qa/start-emulator.sh` creates it on demand and falls back to a manual
  `~/.android/avd` config if avdmanager fails.

## 3. Emulator lifecycle

```bash
qa/start-emulator.sh     # idempotent; nohup emulator, logs to qa/artifacts/emulator.log
qa/wait-emulator.sh      # waits sys.boot_completed=1, asserts API 36/x86_64,
                         # disables animations, writes emulator-boot.txt
qa/stop-emulator.sh      # adb emu kill + PID fallback
qa/keep-emulator-alive.sh  # watchdog, run as a background job during long runs
```

The emulator is launched with
`-no-window -gpu swiftshader_indirect -no-audio -no-boot-anim -no-snapshot -no-metrics -accel on -writable-system`.

**`-writable-system` is required.** Without it `adb remount` fails with
"Device must be bootloader unlocked". On the very first boot with the flag, the
first `adb root && adb remount` disables verity and asks for a reboot; after one
guest reboot the next `adb remount` reports `Remounted /system as RW`. This has
already been done for the current AVD and persists in userdata.

## 4. Fake-root lab

```bash
qa/setup-lab.sh      # build/refresh everything below; idempotent
qa/teardown-lab.sh   # remove only lab fixtures, restore SELinux enforcing
```

What `setup-lab.sh` does:

1. `adb root`, SELinux permissive (`setenforce 0` — image is userdebug).
2. Remount `/system` RW, install fake root manager:
   - `/system/bin/su` (+ `/system/xbin/su`) — see `qa/lab/su`
   - `/system/bin/magisk` (+ `/data/adb/magisk/magisk`, `util_functions.sh`,
     `/data/adb/magisk.db`, `/data/adb/modules`)
3. Create loop-backed fake partitions from `qa/lab/remote-setup.sh`:
   - `test_a` -> 128 MiB loop (`lab_disk_a.img`), mode `brw-rw-rw-`
   - `test_b` -> 128 MiB loop (`lab_disk_b.img`)
   - `persist` -> 64 MiB loop (protected-partition negative test; only when the
     real `persist` does not exist)
   - `dtbo_qa` -> 16 MiB loop (`kind=OTHER`, classified from base name "dtbo_qa")
   - Real `metadata`/`super`/`vbmeta`/`vda`/`vdd` entries are never touched.
4. Make `/data/local/tmp` and `/data/local/tmp/partflasher` `0777` so the app
   uid can stage images and write backups/logs.
5. Write `/data/local/tmp/partflasher/lab-state.sh`, pulled to
   `qa/artifacts/lab-state.env` on the host.

### Fake su contract (`qa/lab/su`)

The app executes every root command as `su -c <command>` (one argv element) and
detects root with an unprivileged `command -v su` probe, then `su -c id`.

| Invocation | Behaviour |
|---|---|
| `su -c id` | prints `uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0` (identity simulated) |
| `su -c id -u` / `id -ur` | prints `0` |
| `su -c --version` | `MAGISK 27.0` |
| `su -v` | `27.0:MAGISK` |
| `su -c '<anything else>'` | `exec /system/bin/sh -c '<anything else>'` **as the caller uid** |
| `su` (no args) / `su -s` | interactive `/system/bin/sh` |

The lab is honest: only identity probes are simulated. Real write commands run
unprivileged, which works because the loop block devices are `0666`, the staging
directory is `0777` and SELinux is permissive. Post-flash verification compares
the actual bytes in the loop device against the fixture.

`MANAGEINFO` (RootManager's root-side probe) run through the fake su emits
`SU_VERSION_BEGIN … 27.0:MAGISK / MAGISK 27.0 … MANAGER_BEGIN`,
`BIN:magisk:/system/bin/magisk`, `MANAGER_END`. `/data/adb` is root-only as on a
real Magisk device, so the app cannot stat `DIR:/data/adb/magisk`; detection
still resolves to Magisk from the version string + binary, which is the
realistic path.

**Root-denied negative test:** move the fake binaries away, relaunch the app,
confirm the UI error, then restore:

```bash
adb shell 'mv /system/bin/su /system/bin/su.denied; mv /system/xbin/su /system/xbin/su.denied'
# ... relaunch app, capture root-denied UI/screenshot/logcat ...
adb shell 'mv /system/bin/su.denied /system/bin/su; mv /system/xbin/su.denied /system/xbin/su'
```

### Known constraints

- `mksh` (Android `/system/bin/sh`) expands `local a=$1 b=$a` **before** binding
  the locals; with `set -u` this aborts. Remote lab scripts therefore declare
  and assign on separate lines. (Bash on the host is fine.)
- All 0–15 loop devices can be occupied by APEX images; always use
  `losetup -f` (e.g. loop54), not a fixed range.
- `/dev/block/by-name` on this image contains real `metadata`, `super`,
  `vbmeta`, `vda`, `vdd`; the lab adds `test_a`, `test_b`, `persist`,
  `dtbo_qa` only.
- App commands are unprivileged, so `su -c 'mount …'` etc. will fail by design.
  Don't test operations that genuinely require kernel root.

## 5. Fixtures

Generated deterministically (fixed headers + pseudo-random payloads) by
`qa/make-fixtures.py`; hash list in `qa/fixtures/SHA256SUMS`.

| File | Purpose |
|---|---|
| `boot_v4.img` / `boot_v3.img` | 4 MiB `ANDROID!` boot images — positive flash |
| `vendor_boot_v4.img` | 8 MiB `VNDRBOOT` |
| `dtbo.img`, `vbmeta.img` | 8 KiB / 64 KiB magic headers |
| `ext4.img`, `erofs.img` | 8 MiB filesystem-looking images |
| `sparse.img` | Android sparse (must be rejected: raw only) |
| `boot_v4.img.gz/.lz4/.zst`, `payload.bin` | compressed / OTA containers (must be rejected) |
| `invalid.bin` | 256 B garbage (unknown format warning) |
| `empty.img` | 0 B (must be rejected) |
| `oversized.img` | 132 MiB boot image (> 128 MiB `test_a`; must be rejected) |

Fixtures are pushed to the emulator with
`adb push qa/fixtures/<file> /sdcard/Download/<file>` (already done for the
key ones). In the SAF picker: content-desc `Show roots` (hamburger) → tap
`Downloads` → tap the file name.

## 6. UI driver (`qa/lib/adbui.py`)

Compose has no stable view ids, so the driver matches nodes by visible
`text`/`content-desc` and taps bounds centres. `uiautomator dump` omits the
`displayed` attribute for visible nodes — the parser treats missing as visible.

```bash
qa/lib/adbui.py screenshot 01-launch        # qa/artifacts/01-launch.png
qa/lib/adbui.py texts                       # visible texts (debugging)
qa/lib/adbui.py dump --file qa/artifacts/ui.xml
qa/lib/adbui.py wait "已获取 Root 权限" --timeout 30
qa/lib/adbui.py tap "重新申请 Root 权限"
qa/lib/adbui.py tap "test_a" --contains
qa/lib/adbui.py tap "Show roots" --desc      # content-desc match
qa/lib/adbui.py tap --xy 540 1200
qa/lib/adbui.py launch | stop
qa/lib/adbui.py rotate 1 | night yes
```

## 7. Commands used for E2E (quick reference)

```bash
source .tooling/dev-env.sh
ADB=.android-sdk/platform-tools/adb
$ADB -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
$ADB -s emulator-5554 shell am start -n com.partflasher.app.debug/com.partflasher.app.MainActivity
$ADB -s emulator-5554 logcat -c
$ADB -s emulator-5554 logcat -d > qa/artifacts/logcat-<step>.txt
$ADB -s emulator-5554 exec-out screencap -p > qa/artifacts/<step>.png
$ADB -s emulator-5554 shell run-as com.partflasher.app.debug ls -l files logs cache
```

Post-flash byte verification (run through fake su as the app does; also possible
as root):

```bash
$ADB shell 'su -c "head -c 4194304 /dev/block/by-name/test_a | sha256sum"'
sha256sum qa/fixtures/boot_v4.img
$ADB shell 'dd if=/dev/block/loop54 bs=1M count=4 2>/dev/null | sha256sum'
```

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `adb remount` → "Device must be bootloader unlocked" | start emulator with `-writable-system`; reboot guest once after first remount |
| `adb root` fails | google_apis image is userdebug; if it still fails the image is not userdebug |
| `su: not found` from app | fake su not installed / not executable (`qa/setup-lab.sh`), or `/system` remount lost after reboot → rerun setup |
| LOOP_SET_FD fails | `losetup -f`; check `/sys/block/loop<N>/loop/backing_file` |
| App sees empty partition list | fake su broken (PartitionRepository needs `su -c` scan), or by-name links missing → rerun setup, check `qa/artifacts/lab-verify.txt` |
| UI dump has no `displayed` attrs | expected; treat missing as visible |
| DocumentsUI shows "No items" | open roots drawer (`Show roots`) → `Downloads` |
