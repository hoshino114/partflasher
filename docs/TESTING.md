# 测试与验证

## 1. 单元测试（JVM，无设备）

```bash
source .tooling/dev-env.sh
./scripts/gradle.sh :app:testDebugUnitTest --console=plain
```

当前结果：**15 个测试类，177 个测试；176 通过，0 失败，1 个环境条件跳过**（当 Gradle 环境存在 `su` 时，缺失 su 的进程启动失败测试自动跳过）。

覆盖范围：

- `ShellScriptBuilder`：单引号/空格/`$()`/`;`/换行注入、NUL 拒绝。
- `AndroidCommandRunner`：超时、丢失 su、stdout/stderr 并发读取、流式回调、部分最后一行。
- `RootManager`：Magisk / KernelSU / APatch 识别与版本归一化、拒绝、超时、无 Root、缓存刷新。
- `PartitionOutputParser` / `PartitionClassifier` / `PartitionRepository`：扫描输出解析、槽位、风险、受保护、挂载、排序、verifyTarget。
- `ImageInspector`：boot v0-v4、vendor_boot、dtbo、vbmeta（大端）、sparse、ext4、EROFS、gzip/lz4/zstd、payload、空/损坏。
- `ImageValidator`：33 条规则矩阵（受保护、容量、格式、槽位、文件名、super/固件/未知）。
- `FlashScriptBuilder` / `FlashPreflight`：分块偏移、尾部非 512 对齐、备份、进度与 SHA 解析、预检硬阻断。
- `FileHasher` / `Format` / `FileLogSink` / `DeviceInfoProvider`。

另有可独立运行的 C SHA-256 测试（`native/build.sh` 会编译并运行，覆盖空串、abc、长消息、百万个 a）。

## 2. Android Lint

```bash
./scripts/gradle.sh :app:lintDebug --console=plain
```

结果：**0 errors**（66 warnings：依赖版本提示、Compose Modifier 参数顺序、API 33 属性提示等，不阻塞构建）。

## 3. API 36 模拟器端到端

环境由 `qa/` 脚本构建：

- Android 16（API 36）google_apis x86_64 模拟器，KVM 加速。
- 假 Root：`/system/bin/su` 对身份探测返回 uid=0，对文件操作执行真实命令；`/data/adb/magisk` + fake magisk 使 UI 走 Magisk 路径。
- `/dev/block/by-name/test_a`、`test_b` 指向 128 MiB loop 设备（0666），`persist` 指向受保护测试 loop。
- 测试镜像：boot v3/v4、vendor_boot、dtbo、vbmeta、ext4、EROFS、sparse、gz/lz4/zst、payload、invalid、oversized。

验证结果（详见 `qa/artifacts/e2e/`）：

| 用例 | 结果 |
|---|---|
| API 36 安装启动、Magisk Root 卡片、设备信息 | PASS |
| 分区列表含 test_a/test_b；persist 受保护不可选 | PASS |
| SAF 选择 boot_v4.img、SHA-256 显示、校验警告 | PASS |
| 二次确认对话框（分区/路径/镜像/SHA/备份/校验/风险勾选） | PASS |
| 刷入 test_a、进度、成功、写后 SHA-256 校验 | PASS |
| loop54 实际字节 SHA-256 与 fixture 一致 | PASS |
| 备份文件 128 MiB 生成 | PASS |
| 日志、指南、深色模式、横屏 | PASS |
| oversized / sparse 镜像被阻断 | PASS |
| Root 被拒绝的错误提示 | PASS |
| release APK（R8、arm64-only）启动冒烟 | PASS（安装/启动/Root/分区列表，无 R8/UnsatisfiedLinkError） |
| 缺少 sha256sum 的设备 | PASS（写入成功，校验跳过并警告，不误报失败） |
| 哈希后端 | PASS（native-sha256 实际加载；release R8 保留 JNI) |

复现入口：

```bash
qa/start-emulator.sh
qa/wait-emulator.sh
qa/setup-lab.sh
# 安装 debug APK 后运行 qa 驱动脚本
qa/teardown-lab.sh
```

## 4. 构建产物检查

```bash
./scripts/package-dist.sh
unzip -l dist/PartFlasher-1.0.0-arm64-release.apk | grep libpartflasher
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging dist/...apk
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 dist/...apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose dist/...apk
```

正式包：targetSdk 36、只含 `lib/arm64-v8a/libpartflasher.so`、16 KB 页对齐、v2 签名（开发密钥）。
调试包：同时含 arm64-v8a + x86_64，便于模拟器 QA。

完整 E2E 报告与证据：[`qa/REPORT.md`](../qa/REPORT.md)。
