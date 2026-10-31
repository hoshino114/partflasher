# PartFlasher 实现说明

## 1. 分层

```
Compose UI (ui/)  ──>  MainViewModel (viewmodel/)  ──>  AppContainer (core/)
                                                        ├─ RootManager / AndroidCommandRunner
                                                        ├─ PartitionRepository / PartitionClassifier
                                                        ├─ ImageStager / ImageInspector / FileHasher
                                                        ├─ ImageValidator / FlashPreflight
                                                        ├─ FlashController / FlashScriptBuilder
                                                        ├─ LogRepository / FileLogSink
                                                        └─ DeviceInfoProvider
```

- UI 不直接执行 shell，只调用 ViewModel；ViewModel 不持有 Activity/Context 生命周期，所有耗时操作在 viewModelScope 或 app-scope 中执行。
- `FlashController` 由 Application 级 `AppContainer` 持有，旋转/切后台不会取消刷写协程；Activity 侧保持屏幕常亮并显示不可取消进度框。
- 模型只包含不可变数据，UI 根据状态计算按钮可用性。

## 2. Root 流程

1. 启动后 `MainViewModel.bootstrap()` 调用 `RootManager.detect(request = true)`。
2. 非 Root 探测 `sh -c` 查找 su 路径（不触发弹窗）。
3. 授权请求通过 `su -c id` 完成，90 秒超时；解析 `uid=0`。
4. 授权后通过一次 Root 脚本收集 Magisk/KernelSU/APatch 目录、`su -v`/`--version`、管理器二进制与版本，并归一化为单一版本号。
5. 刷写前再次 `ensureRoot()` 静默校验；Root 丢失即中止，不进行任何写操作。

## 3. 分区扫描

一条 Root 脚本扫描：

- `/dev/block/by-name`
- `/dev/block/bootdevice/by-name`
- `/dev/block/platform/*/by-name`
- `/dev/block/platform/*/*/by-name`

输出 `name|link|resolved|size`，随后输出活动槽位与 `/proc/mounts`。容量优先 `blockdev --getsize64`，失败回退 `/sys/class/block/<dev>/size`。Kotlin 侧去重、分类：

- 分区类型：BOOT / RECOVERY / INIT_BOOT / VENDOR_BOOT / DTBO / VBMETA / SUPER / SYSTEM / VENDOR / PRODUCT / ODM / DATA / METADATA / FIRMWARE / MODEM / MISC / PERSIST / OTHER。
- 风险：受保护分区 CRITICAL；boot 系/固件 CAUTION；其余 NORMAL。
- 排序：boot、init_boot、vendor_boot、dtbo、vbmeta、recovery、super 优先，其余按名称。

刷写前 `verifyTarget()` 重新 `readlink -f`，确认仍在 `/dev/block/`、为块设备、解析路径与扫描时一致、容量未变。

## 4. 镜像处理

`ImageStager` 将 SAF URI 一次性流式复制到 `cacheDir/staging/`，同时更新 MessageDigest；完成后再交给 `ImageInspector` 读取最多 4 KiB 头：

| 格式 | 识别方式 |
|---|---|
| Android boot | `ANDROID!`，解析 header v0-v4、kernel/ramdisk/header_size |
| vendor_boot | `VNDRBOOT`，解析 header v3/v4 |
| dtbo | LE magic `0xd7b7ab1e`，解析条目数与总大小 |
| vbmeta | `AVB0`，**大端**字段，解析版本/flags/release |
| sparse | LE magic `0xed26ff3a`，计算展开大小（v1 拒绝刷入） |
| ext4 | 超级块偏移 0x438 magic `0xEF53` |
| EROFS | 超级块偏移 1024 magic `0xE0F5E1E2` |
| 压缩/OTA | gzip / LZ4 / Zstd / `CrAU` payload（拒绝刷入） |

`FileHasher` 优先调用 arm64/x86_64 的 JNI SHA-256（`native/`），不可用时回退 Java MessageDigest；暂存阶段使用单遍 MessageDigest 以避免二次读盘。

## 5. 刷写事务

`FlashController.start()` 在应用级协程中执行：

1. **Preflight**：`FlashPreflight` 重新运行校验矩阵（受保护分区、格式/分区类型、sparse/压缩、容量、空文件），并使用传入的 `ImageValidator`；任何 ERROR 立即失败。
2. **目标复核**：`PartitionRepository.verifyTarget()`。
3. **Root 复核**：`ensureRoot()`。
4. **空间检查**：`df -Pk /data/local/tmp`，需要镜像 + 备份 + 16 MiB 余量。
5. **复制到 Root 可读路径**：`cp` 到 `/data/local/tmp/partflasher/`，用 `wc -c` 校验大小；规避 SELinux 对应用私有目录的限制。
6. **可选备份**：分块读取分区到 `backup_<分区>_<时间戳>.img`，必须完整成功后才继续；>512 MiB 拒绝备份并中止（用户可关闭备份重试）。
7. **分块写入**：4 MiB `dd skip/seek/count=1 conv=notrunc`，最后不足 4 MiB 的尾部按 512 字节 + 1 字节处理；每块输出 `PROGRESS:<written>:<total>` 驱动进度、速度与耗时。
8. **sync**。
9. **可选回读校验**：`head -c <size> <分区> | sha256sum` 与暂存镜像 SHA-256 比对；不一致即失败并给出备份路径。
10. **清理**：删除临时镜像；备份保留；释放 wakelock。

写入阶段没有取消 API，失败不自动重试。异常/取消路径会标记分区可能不完整并提示备份恢复。

## 6. 安全不变量（代码强制）

1. 只能写扫描结果中的分区，且刷前重新解析符号链接。
2. 受保护分区双重阻断（Repository/Validator/Preflight）。
3. 镜像大小必须 ≤ 分区容量，且 >1 KiB。
4. 格式与分区类型硬匹配（boot/vendor_boot/dtbo/vbmeta 等）；sparse/压缩/payload 拒绝。
5. 备份失败在写入前中止。
6. 写入后 `sync`，校验失败视为失败。
7. Root 拒绝/丢失时绝不写入。
8. 所有命令参数经 POSIX 单引号转义，NUL 拒绝。
9. 刷写期间不可取消、不自动重试；保持前台并由 wakelock 防止休眠。
10. 日志记录所有命令、阶段和错误，便于事后恢复。

## 7. 并发与生命周期

- `AppContainer` 在 Application 创建一次；`FlashController` 使用 `SupervisorJob + Dispatchers.IO`。
- 屏幕旋转不会取消刷写；`MainActivity` 收集 `flashState` 保持屏幕常亮。
- 进程被系统杀死时正在进行的写操作仍可能中断——UI 明确提示风险。
