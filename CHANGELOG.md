# Changelog

## v1.0.0 — 2026-10-31

首个公开版本。

### 功能

- 启动自动检测并申请 Root 权限，兼容 Magisk / KernelSU / APatch。
- 自动扫描并展示本机 by-name 分区（含回退路径、A/B 槽位、容量、挂载状态、风险等级）。
- 常用分区快捷卡片：boot / recovery / init_boot / vendor_boot / dtbo / vbmeta。
- SAF 选择 IMG，支持 Android boot、vendor_boot、dtbo、vbmeta、ext4、EROFS raw 镜像。
- 镜像 SHA-256、格式头、分区类型、容量匹配校验；受保护分区硬阻断。
- 刷入前信息总览与强制二次确认；super/固件/未知格式额外确认。
- 可选分区备份（≤512 MiB）与写入后回读 SHA-256 校验。
- 实时日志、持久化日志文件、成功/失败结果提示、复制/导出/清空。
- Material Design 3：动态取色、明暗主题、edge-to-edge、底部导航、横竖屏适配。

### 安全与质量

- 4 MiB 分块 `dd` + `conv=notrunc` + `sync`，写入阶段不可取消、失败不自动重试。
- 刷前重新解析分区符号链接并校验容量/块设备属性。
- 177 个 JVM 单元测试；API 36 模拟器 + 假 Root + loop 分区端到端验证（字节级 SHA-256 比对通过）。
- Release APK 仅含 arm64-v8a，16 KB 页对齐；R8 保留 JNI 哈希实现。

### 已知限制

- 仅支持 raw IMG；sparse / gzip / LZ4 / Zstd / payload.bin 会被阻断并提示转换。
- 当前使用开发调试签名，正式分发前请替换为自己的 keystore。
- 真实设备上的 Magisk/KernelSU/APatch 授权弹窗与厂商 SELinux 策略建议先在非关键分区实测。
