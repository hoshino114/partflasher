# PartFlasher E2E QA report — API 36 emulator + fake root

Date: 2026-10-31 (Asia/Shanghai)
Owner: qa-lab (task-3)
Status: **PASS** — all acceptance criteria for task-3 met; 2 minor non-blocking
findings reported in §8.

## 1. Builds under test

| Artifact | SHA-256 | Notes |
|---|---|---|
| `qa/artifacts/app-debug-final.apk` (final debug, 20:59) | `d0787a2d3d51ff61117e4b21d9264d27cf7dec70f1b0b909e5327b3fa2a2a18d` | `com.partflasher.app.debug`, `-PallAbis` (arm64-v8a + x86_64), versionName `1.0.0-debug` |
| `qa/artifacts/app-release-final.apk` (release, 20:58) | `2c1b588b41972aa4e7a1c526480a0f298d47e5d7fd134d06d2ac05b19ee3101d` | `com.partflasher.app`, R8 minified, arm64-only, debug-key signed |

Earlier builds (20:45 / 20:47 / 20:49 / 20:53) were used during the run; the
positive flow was re-run end-to-end on the final debug build. Screenshots are
labelled by build (`b2052-*` = 20:53 build, `final-*` = final 20:59 build).

## 2. Environment

- Emulator: `emulator-5554`, AVD `partflasher_api36` (Pixel 6), headless,
  KVM, `-writable-system`.
- Image: `system-images;android-36;google_apis;x86_64` rev 7 (userdebug),
  `ro.build.version.sdk=36`, `ro.build.version.release=16`,
  `ro.product.cpu.abi=x86_64`; SELinux Permissive for the lab.
- Fake root: `/system/bin/su` (+`/system/xbin/su`), `/system/bin/magisk`,
  `/data/adb/magisk`; `su -c id` → uid=0 root (identity only).
- Loop partitions: `test_a` → loop54 (128 MiB), `test_b` → loop55 (128 MiB),
  `persist` → loop58 (64 MiB protected fixture); real `metadata`/`super`/
  `vbmeta` untouched. Staging `/data/local/tmp/partflasher` is 0777.
- Fixtures: `qa/fixtures/` (deterministic, `SHA256SUMS`); pushed to
  `/sdcard/Download/`.

## 3. Positive E2E on final debug build — PASS

| Step | Result | Evidence |
|---|---|---|
| Install/launch | `adb install -r -t` Success; cold start 1.2 s | `e2e/final-01-root-card.png` |
| Root card | “已获取 Root 权限”, `Magisk · 27.0`, uid=0, 成功 | `e2e/final-01-root-card.png` |
| Device info | Google sdk_gphone64_x86_64, Android 16 (SDK 36), 无（非 A/B）, x86_64+arm64-v8a, 模拟器 | `e2e/final-01-root-card.png` |
| Partition scan | super/metadata/persist/dtbo_qa/test_a/test_b/vda/vdd; test_a & test_b selectable; persist/metadata protected | `e2e/final-02-target.png` |
| SAF pick | DocumentsUI: Show roots → Downloads → `boot_v4.img` | `e2e/final-02-target.png`, `e2e/b2052-04-selection` family |
| Staging + validation | size 4.0 MB, format Android boot (header v4), SHA-256 `a183a4f9…cae8de`; 校验通过，可以刷入 (2 info hints) | `e2e/final-02-target.png` |
| Confirm dialog | 目标分区 name/path/128 MB/slot, image name/size/format/SHA-256, backup switch (default off for non-boot-family), verify switch (on), mandatory risk checkbox, destructive confirm | `e2e/final-03-confirm-dialog.png`, `e2e/final-03b-confirm-checked.png` |
| Flash progress | non-dismissible dialog: 正在准备 → 正在备份原分区 (progress %), live log, “写入开始后无法取消” | `e2e/final-progress-01..10.png` |
| Result | 刷入成功, 耗时 00:01, 写入校验 已通过（SHA-256 一致）, 写入路径 `/dev/block/loop54`, backup path `/data/local/tmp/partflasher/backup_test_a_<ts>.img` | `e2e/final-04-result.png` |
| App log file | `files/logs/session-20261031.log`: staging log ends `哈希后端=native-sha256/1.0 (arm64)`; `写入校验通过：SHA-256 一致` | `e2e/final-app-log-session.txt` |
| Logs screen | 61 entries with level filters/search/copy/export/clear | `e2e/final-08-logs.png` |
| Guide screen | safety rules, steps, root-manager help mentions Magisk/KernelSU/APatch | `e2e/b2052-15-guide.png` |
| Dark/light | correct colors both modes | `e2e/b2052-16-dark-guide.png`, `e2e/b2052-17-dark-logs.png`, `e2e/final-01-root-card.png` |
| Rotation | landscape renders and state survives | `e2e/b2052-18-landscape-logs.png` |
| README material | root card light, prepared flash screen, confirm dialog | `qa/artifacts/readme/*.png` |

### Byte-level verification (the important one)

```
fixture  qa/fixtures/boot_v4.img
         a183a4f9da8519874cf6d3c379f8d4a57c010f3f2aef3bfe70de6f4910cae8de
loop54   134217728 bytes; first 4 MiB (read through fake su / app path)
         a183a4f9da8519874cf6d3c379f8d4a57c010f3f2aef3bfe70de6f4910cae8de  -
backup   134217728 bytes, owner u0_a216 (app uid), created by the app
```

`e2e/final-verify-bytes.txt`, plus the app’s own `写入校验 已通过` result.
Independent `dd if=/dev/block/loop54 bs=1M count=4 | sha256sum` matched too.

## 4. Negative tests — PASS

| Case | Expected | Observed | Evidence |
|---|---|---|---|
| Protected partition (`persist`) | not selectable, reason shown | row tap shows snackbar “校准数据分区（persist）…” and selection stays `test_a` | `e2e/b2052-11-protected-snackbar.png` |
| Oversized image (132 MiB > 128 MiB `test_a`) | blocked with reason | 校验未通过 / “镜像大小（132.00 MiB）超过分区容量（128.00 MiB）…已阻止刷入”, confirm disabled | `e2e/b2052-09-oversized-blocked.png` |
| Android sparse image | blocked, raw-only message | “检测到 Android sparse 镜像…请先使用 simg2img…” | `e2e/b2052-10-sparse-blocked.png` |
| Root denied | clear error + guidance | after removing fake `su`, relaunch shows “未检测到 Root 权限” + Magisk/KernelSU/APatch guidance; restore `su` + tap retry → granted again | `e2e/b2052-12-root-denied.png`, `e2e/b2052-13-root-restored.png`, `e2e/b2052-logcat-root-denied.txt` |
| Missing `sha256sum` | graceful handling | hidden `/system/bin` + `/vendor/bin` sha256sum: write completes, verification is **skipped** with WARN “设备缺少 sha256sum 命令…本次校验已跳过”, result 刷入成功 with 写入校验=未校验; bytes on loop55 still match fixture; binaries restored | `e2e/final-07-sha256sum-skipped-result.png`, `e2e/final-app-log-missing-sha256sum.txt` |

## 5. Release APK smoke — PASS

`qa/artifacts/e2e/release-smoke.txt`

- `aapt2`: package `com.partflasher.app`, targetSdk 36, `native-code: 'arm64-v8a'` (expected on arm64 release; **arm64-only is expected/acceptable on the x86_64 emulator**, native hash falls back to JVM).
- `adb install -r -t app-release.apk` → Success; cold start 0.66 s.
- Root card: 已获取 Root 权限, `Magisk · 27.0`; partition scan shows test_a/test_b/persist.
- `run-as` correctly rejected: “package not debuggable” (no debug flag in release).
- No `FATAL EXCEPTION` / `UnsatisfiedLinkError` / R8-related crashes in logcat.
- Uninstalled afterwards; debug build remains installed for follow-up testing.

## 6. Reproduce

```bash
cd /path/to/part-flasher
source .tooling/dev-env.sh
qa/start-emulator.sh && qa/wait-emulator.sh      # emulator (already running)
qa/setup-lab.sh                                  # fake su + loop partitions + 0777 staging
ADB=.android-sdk/platform-tools/adb
$ADB -s emulator-5554 install -r -t qa/artifacts/app-debug-final.apk
$ADB -s emulator-5554 shell pm clear com.partflasher.app.debug
$ADB -s emulator-5554 shell am start -n com.partflasher.app.debug/com.partflasher.app.MainActivity
# UI driving: qa/lib/adbui.py (dump/texts/tap/wait/screenshot)
# flash fixture:
$ADB -s emulator-5554 push qa/fixtures/boot_v4.img /sdcard/Download/
# verify:
$ADB -s emulator-5554 shell 'su -c "head -c 4194304 /dev/block/loop54 | sha256sum"'
sha256sum qa/fixtures/boot_v4.img
# app log:
$ADB -s emulator-5554 shell 'run-as com.partflasher.app.debug cat files/logs/session-20261031.log'
```

## 7. Observations that are not app bugs

- The fake `su` answers both `su -v` and `su --version`, so the old UI joined
  both into “27.0:MAGISK MAGISK 27.0”; the final build normalizes to
  “Magisk · 27.0” — verified fixed.
- `persist`/`metadata` row taps intentionally show a reason snackbar instead of
  silently ignoring the tap (see `PartitionRow.kt` comment: “protected rows stay
  tappable only so the reason can be explained”). This satisfies the “not
  selectable” requirement; it is not literally `enabled=false`.
- `/data/adb` is root-only (as on real Magisk), so the app cannot stat
  `DIR:/data/adb/magisk`; root detection correctly uses the `su` version output
  and `magisk` binary — realistic behaviour.

## 8. Findings (minor, non-blocking)

**F1 — native version string is hard-coded to arm64 on x86_64 builds (cosmetic).**
On the x86_64 emulator the x86_64 lib loads and works, but its version string
reports `native-sha256/1.0 (arm64)`:
`app/src/main/.../native/src/partflasher_jni.c:64` returns the literal
`"native-sha256/1.0 (arm64)"`. Diagnostics/log lines therefore say
`哈希后端=native-sha256/1.0 (arm64)` on an x86_64 device.
Suggested fix: `#if defined(__aarch64__) … #elif defined(__x86_64__) …` (or
`#ifdef __arm__` etc.) in the JNI version string.

**F2 — mandatory risk checkbox starts clipped inside the scrollable dialog.**
On 1080×2400 the confirm dialog’s scrollable content ends below the switch rows;
the “我已了解风险，确认将以上镜像写入所选分区” checkbox is off-screen until
the user scrolls inside the dialog. The destructive button is visible but
disabled, with no visible hint that the content scrolls. Repro:
open 确认刷入 for `test_a` + `boot_v4.img` (`e2e/final-03-confirm-dialog.png`),
then swipe up inside the dialog (`e2e/final-03b-confirm-checked.png`).
Suggested fix: move the acknowledgement row outside the `verticalScroll`
column (pin above the buttons) or auto-scroll to it when the disabled confirm
button is tapped.

**No blocking bugs found.** The final debug build passed the full positive and
negative matrix; release build passed the smoke matrix.

## 9. Artifact index

- Screenshots: `qa/artifacts/e2e/*.png` (final-build evidence `final-*`,
  earlier-build evidence `b2052-*`, release `release-*`).
- README material: `qa/artifacts/readme/{readme-root-card-light,readme-flash-prepared,readme-confirm-dialog}.png`.
- Transcripts: `qa/artifacts/e2e/final-verify-bytes.txt`,
  `final-app-log-session.txt`, `final-app-log-missing-sha256sum.txt`,
  `release-smoke.txt`, `b2052-verify-bytes.txt`, `logcat-build2049-full.txt`.
- Lab: `qa/README.md`, `qa/artifacts/lab-setup.txt`, `qa/artifacts/lab-verify.txt`,
  `qa/artifacts/emulator-boot.txt`, `qa/artifacts/emulator.log`.
- APKs tested: `qa/artifacts/app-debug-final.apk`, `qa/artifacts/app-release-final.apk`
  (+ intermediate snapshots `app-debug-e2e.apk`, `app-debug-e2e-2052.apk`,
  `app-release-e2e.apk`).

## 10. Lab lifecycle re-check (after E2E)

`qa/teardown-lab.sh` → `qa/setup-lab.sh` → `qa/setup-lab.sh` was run again to
prove both scripts are idempotent on the final scripts:

- Teardown removed the fake `su`/`magisk`, all lab by-name links, detached lab
  loops (including leftovers) and restored SELinux enforcing.
- Re-setup recreated `test_a` (loop54, 128 MiB), `test_b` (loop55, 128 MiB),
  `persist` protected fixture, `dtbo_qa`; the second re-run logged
  `reusing existing protected fixture persist -> /dev/block/loop56` and created
  no duplicate links.
- App relaunch after the rebuild: “已获取 Root 权限 / Magisk · 27.0”, partition
  scan shows `test_a`/`test_b`/`persist` again
  (`qa/artifacts/e2e/final-09-lab-reset-app-check.png`).
- Emulator `emulator-5554` left booted (API 36 x86_64), keepalive watchdog
  `qa/keep-emulator-alive.sh` running as a managed background job; debug APK
  `com.partflasher.app.debug` installed.
