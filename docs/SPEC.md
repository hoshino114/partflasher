# PartFlasher — product & engineering specification

Android app that flashes raw IMG files into on-device block partitions using root
(Magisk / KernelSU / APatch). UI language is Simplified Chinese. Material Design 3.

## 1. Non-negotiable requirements

| # | Requirement | Acceptance |
|---|---|---|
| R1 | Android 16 compatible, arm64 target | compileSdk/targetSdk 36, minSdk 26, release APK contains `lib/arm64-v8a/libpartflasher.so`, installs on API 36 arm64 |
| R2 | Root request/detection, Magisk/KernelSU/APatch | On launch: detect + trigger `su` prompt; show solution, version, uid; retry; clear denial guidance |
| R3 | Auto partition list | Scan `/dev/block/by-name` + fallbacks; show name, size, path, slot, risk; active slot marked |
| R4 | Manual target + IMG pick, flash | SAF picker, copy+hash image, confirm, chunked `dd` write, sync |
| R5 | Quick recovery/boot etc | Quick cards for boot/recovery/init_boot/vendor_boot/dtbo/vbmeta that pre-select active slot and open picker |
| R6 | Pre-flash summary + explicit second confirm | Dialog lists partition name/path/size, image name/size/SHA-256, detected format, backup/verify toggles; confirm requires checkbox |
| R7 | Logs, success/failure, exception handling | Live log screen, persistent log file, success/failure dialog, clear error stages |
| R8 | Material Design 3 | Compose Material3, dynamic color, edge-to-edge, light/dark, bottom navigation |
| R9 | Root detection / partition accuracy / image validation / anti-brick / logging | see section 4 safety invariants |

## 2. Architecture

Single `:app` module (Kotlin 2.2, AGP 8.11.1, Gradle 8.13, JDK 17, Compose BOM 2025.06.01).

```
com.partflasher.app
  core/
    model/        immutable UI/domain models (owned by Lead, do not reshape)
    root/         CommandRunner (interface), AndroidCommandRunner, RootManager
    partition/    PartitionRepository, PartitionClassifier
    image/        ImageInspector, ImageStager
    validation/   ImageValidator
    flash/        FlashController, FlashScriptBuilder, BackupManager
    log/          LogRepository, FileLogSink
    device/       DeviceInfoProvider
    util/         FileHasher, NativeSha256, Format
  ui/             Compose theme + screens + components
  viewmodel/      MainViewModel + MainUiState
  MainActivity.kt, PartFlasherApplication.kt
```

The app container (`core/AppContainer.kt`) owns singletons and is created by
`PartFlasherApplication`. `MainViewModel` consumes the container only.

## 3. Core contracts already in the workspace

Models in `core/model/*` and APIs in `core/root/CommandRunner.kt`,
`RootManager`, `PartitionRepository`, `ImageInspector`, `ImageStager`,
`ImageValidator`, `FlashController`, `LogRepository`, `FileHasher`,
`DeviceInfoProvider`, `AppContainer` are the integration contract. Core owner
may add members but must not break these signatures without messaging Lead + UI.

Shell rules:
- Every root command is executed as `su -c <single-quoted command>` and never
  built from unescaped user text. Use `ShellScriptBuilder.quote()` for all values.
- Partition writes are chunked (4 MiB) with `dd bs=4194304 skip=N seek=N conv=notrunc`
  so progress can be reported; `sync` before verification.
- `runStreaming` must read stdout/stderr concurrently and never deadlock.
- Timeouts: detect/request up to 90 s (manager prompt); normal commands 30 s;
  flash no fixed timeout, progress-line based.

## 4. Safety invariants (must enforce server-side, not just UI)

1. Only partitions discovered from the scan list can be written; target path must
   be re-resolved (`readlink -f`) and `test -b` immediately before write.
2. Protected partitions are blocked with a reason: userdata, metadata, persist,
   frp, devinfo, keystore, misc, ssd, modemst1/modemst2, fsg, efs.
3. Reject image > partition size; reject empty/zero or absurdly small images.
4. Reject Android sparse / compressed / payload images with actionable message
   (v1 supports raw only); validate boot-family headers against target kind.
5. Backup (default on for boot family, <= 512 MiB) must succeed before writing;
   backup goes to `/data/local/tmp/partflasher/backup_<name>_<timestamp>.img`.
6. After write, `sync`; optional read-back SHA-256 via `head -c <size> <part> | sha256sum`
   and compare with staged image hash. Mismatch = failure with backup path.
7. Root denial/loss aborts before any write. Backup failure aborts before write.
8. Image staging copies to app cache, then root copies to `/data/local/tmp/partflasher/`
   before flashing so SELinux cannot block the dd source.
9. Never auto-retry a failed write or allow cancel during the write phase.
10. Keep screen on during flash; state survives activity recreation (controller is app-scoped).

## 5. UX outline (MD3)

Three destinations: 刷入 / 日志 / 指南.
- 刷入: root status card (solution chip, version, retry), device card (model,
  Android, ABI, slot), risk banner, "常用分区" quick cards, selected image/target
  card with validation issues, searchable all-partition list (risk badges,
  protected rows disabled), flash CTA.
- Confirm dialog: red warning icon, detail rows including partition path + SHA-256,
  backup + verify switches, mandatory checkbox, destructive confirm button.
- Flash progress: non-dismissible dialog/overlay, LinearProgressIndicator,
  bytes/speed/elapsed, "请勿退出、断电" warning, live log tail; result dialog.
- 日志: level filters, search, copy/export/clear, auto-scroll, monospace.
- 指南: safety rules, step-by-step, root manager help, supported formats, FAQ,
  version/disclaimer. Root-denied help must mention Magisk/KernelSU/APatch.
- Light+dark, dynamic color on Android 12+, edge-to-edge, no hardcoded colors in
  screens (use MaterialTheme), 48 dp touch targets, content descriptions,
  all user-visible text in `res/values/strings.xml` (Chinese default).

## 6. Build/test

```
source .tooling/dev-env.sh
scripts/gradle.sh :app:assembleDebug          # x86_64 + arm64 (emulator-ready)
scripts/gradle.sh :app:assembleRelease        # arm64 only
scripts/gradle.sh :app:testDebugUnitTest
scripts/gradle.sh :app:lintDebug
```
`-PallAbis` is implicit for debug? No: debug uses both ABIs by default in current
build config; release filters arm64 (see app/build.gradle.kts). Keep that behavior.

Emulator package id is `com.partflasher.app.debug`; emulator QA uses a fake su.

## 7. Definition of done

- Builds cleanly; unit tests cover parsing/hashing/validation/scripts/safety rules.
- APK verified: manifest targetSdk 36, native lib arm64-v8a (16 KB aligned), no debug flag in release.
- End-to-end smoke test on API 36 emulator with fake su + loop-backed partition:
  root card, partition list, image pick, validation, confirm, flash, verify, log.
- README (Chinese) with install, build, usage, safety and architecture.
