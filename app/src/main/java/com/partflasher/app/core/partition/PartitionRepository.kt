package com.partflasher.app.core.partition

import com.partflasher.app.core.log.LogRepository
import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionScanResult
import com.partflasher.app.core.root.RootAccessException
import com.partflasher.app.core.root.RootManager
import com.partflasher.app.core.root.ShellScriptBuilder
import com.partflasher.app.core.root.ShellResult
import kotlinx.coroutines.CancellationException

/**
 * Reads the real partition table through the root shell and performs the
 * just-before-write target re-validation.
 */
class PartitionRepository(
    private val rootManager: RootManager,
    private val log: LogRepository? = null,
) {

    /**
     * Scans all known by-name directories. Requires an already granted root
     * session: this method never triggers a new `su` prompt. On failure it
     * returns an empty result with user-facing [PartitionScanResult.warnings]
     * instead of throwing.
     */
    suspend fun load(): PartitionScanResult {
        try {
            rootManager.ensureRoot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RootAccessException) {
            val message = "需要 Root 权限才能读取分区表：${e.message ?: "请先授权 Root"}"
            log?.error(TAG, message)
            return PartitionScanResult(warnings = listOf(message))
        }

        log?.info(TAG, "开始扫描设备分区表…")
        val result: ShellResult = try {
            rootManager.run(SCAN_SCRIPT, SCAN_TIMEOUT_MILLIS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "扫描分区表失败：${e.message ?: e.javaClass.simpleName}"
            log?.error(TAG, message)
            return PartitionScanResult(warnings = listOf(message))
        }

        if (result.timedOut) {
            val message = "扫描分区表超时（$SCAN_TIMEOUT_MILLIS ms），请重试。"
            log?.error(TAG, message)
            return PartitionScanResult(warnings = listOf(message))
        }
        if (!result.isSuccess) {
            val detail = result.combinedOutput.take(400)
            val message = "扫描分区表失败（退出码 ${result.exitCode}）：$detail"
            log?.error(TAG, message)
            return PartitionScanResult(warnings = listOf("扫描分区表失败，请确认 Root 权限正常后重试。"))
        }
        if (result.stderr.isNotBlank()) {
            log?.debug(TAG, "扫描 stderr：${result.stderr.trim().take(500)}")
        }

        val parsed = PartitionOutputParser.parse(result.stdout)
        val warnings = parsed.warnings.toMutableList()
        val seenNames = LinkedHashMap<String, RawPartition>()
        for (raw in parsed.partitions) {
            val existing = seenNames[raw.name]
            when {
                existing == null -> seenNames[raw.name] = raw
                existing.blockPath != raw.blockPath -> warnings.add(
                    "发现重名分区 ${raw.name}（${existing.blockPath} 与 ${raw.blockPath}），已保留第一个。",
                )
                else -> Unit
            }
        }

        val mounts = parsed.mounts
        val partitions = seenNames.values.map { raw ->
            val mountPoint = PartitionOutputParser.mountPointFor(raw.blockPath, raw.linkPath, mounts)
            PartitionClassifier.classify(
                name = raw.name,
                linkPath = raw.linkPath,
                blockPath = raw.blockPath,
                sizeBytes = raw.sizeBytes,
                isMounted = mountPoint != null,
                mountPoint = mountPoint,
            )
        }.sortedWith(
            compareBy<Partition>({ PartitionClassifier.sortOrder(it.kind) }, { it.name.lowercase() }),
        )

        if (partitions.isEmpty()) {
            warnings.add("未能发现任何分区，可能是设备不兼容或 Root 权限不足。")
            log?.warn(TAG, warnings.last())
        }
        val unknownSizeCount = partitions.count { it.sizeBytes <= 0L }
        if (unknownSizeCount > 0) {
            warnings.add("有 $unknownSizeCount 个分区无法读取大小，刷入前请确认目标分区。")
        }
        val sourceDirectory = partitions.firstOrNull()?.linkPath?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
        val activeSlot = parsed.activeSlot
        log?.info(
            TAG,
            "分区扫描完成：${partitions.size} 个分区，来源 ${sourceDirectory ?: "未知"}，" +
                "活动槽位 ${activeSlot ?: "无（非 A/B）"}",
        )
        if (warnings.isNotEmpty()) {
            log?.warn(TAG, "分区扫描警告：${warnings.joinToString("；")}")
        }
        return PartitionScanResult(
            partitions = partitions,
            sourceDirectory = sourceDirectory,
            activeSlot = activeSlot,
            warnings = warnings,
        )
    }

    /**
     * Re-resolves the by-name symlink directly before a destructive write:
     * checks the link still exists, resolves to a path under `/dev/block`, is a
     * block device and still has the same size as during the scan.
     */
    suspend fun verifyTarget(partition: Partition): TargetCheck {
        val script = buildVerifyTargetScript(partition)
        log?.command(TAG, script)
        val result: ShellResult = try {
            rootManager.run(script, VERIFY_TIMEOUT_MILLIS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "无法校验目标分区：${e.message ?: e.javaClass.simpleName}"
            log?.error(TAG, message)
            return TargetCheck(ok = false, message = message)
        }
        if (result.timedOut) {
            return TargetCheck(ok = false, message = "目标分区校验超时，已阻止写入，请重试。")
        }
        if (!result.isSuccess) {
            val firstLine = result.firstLine
            val message = when {
                firstLine.contains("TARGET_MISSING", ignoreCase = true) || result.exitCode == 2 ->
                    "目标分区已不存在或无法访问（${partition.linkPath}），已阻止写入。请重新扫描分区。"

                firstLine.isNotBlank() -> "目标分区校验失败：$firstLine"
                else -> "目标分区校验失败（退出码 ${result.exitCode}），已阻止写入。"
            }
            log?.error(TAG, message)
            return TargetCheck(ok = false, message = message)
        }

        val resolved = firstValue(result.stdout, "RESOLVED:")
        val isBlock = firstValue(result.stdout, "IS_BLOCK:") == "1"
        val reportedSize = firstValue(result.stdout, "SIZE:")?.toLongOrNull()

        if (resolved.isNullOrBlank()) {
            return TargetCheck(ok = false, message = "无法解析目标分区的真实路径，已阻止写入。")
        }
        if (!resolved.startsWith("/dev/block/")) {
            return TargetCheck(
                ok = false,
                message = "目标真实路径不在 /dev/block 下（$resolved），已阻止写入。",
            )
        }
        if (!isBlock) {
            return TargetCheck(
                ok = false,
                message = "目标路径不是块设备（$resolved），已阻止写入。",
            )
        }
        if (resolved != partition.blockPath) {
            return TargetCheck(
                ok = false,
                message = "分区映射已变化（扫描时 ${partition.blockPath}，当前 $resolved），" +
                    "可能发生了 OTA 切换或重新分区，请重新扫描分区后再试。",
            )
        }
        if (partition.sizeBytes > 0L) {
            if (reportedSize == null || reportedSize <= 0L) {
                return TargetCheck(
                    ok = false,
                    message = "无法读取目标分区大小，无法确认分区未被修改，已阻止写入。",
                )
            }
            if (reportedSize != partition.sizeBytes) {
                return TargetCheck(
                    ok = false,
                    message = "分区大小已变化（扫描时 ${partition.sizeBytes} 字节，当前 $reportedSize 字节），" +
                        "已阻止写入，请重新扫描分区。",
                )
            }
        }
        val message = "目标校验通过：${partition.name} → $resolved（${partition.sizeBytes} 字节）"
        log?.info(TAG, message)
        return TargetCheck(ok = true, message = message)
    }

    private fun firstValue(stdout: String, prefix: String): String? =
        stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()

    private fun buildVerifyTargetScript(partition: Partition): String = ShellScriptBuilder.build(
        "link=${ShellScriptBuilder.quote(partition.linkPath)}",
        "if [ ! -e \"\$link\" ]; then echo 'TARGET_MISSING'; exit 2; fi",
        "resolved=\$(readlink -f \"\$link\" 2>/dev/null)",
        "if [ -z \"\$resolved\" ]; then resolved=\"\$link\"; fi",
        "echo \"RESOLVED:\$resolved\"",
        "if [ -b \"\$resolved\" ]; then echo 'IS_BLOCK:1'; else echo 'IS_BLOCK:0'; fi",
        "sz=''",
        "if command -v blockdev >/dev/null 2>&1; then sz=\$(blockdev --getsize64 \"\$resolved\" 2>/dev/null); fi",
        "case \"\$sz\" in ''|*[!0-9]*) sz='' ;; esac",
        "if [ -z \"\$sz\" ]; then",
        "  dev=\"\${resolved##*/}\"",
        "  sectors=\$(cat \"/sys/class/block/\$dev/size\" 2>/dev/null)",
        "  case \"\$sectors\" in ''|*[!0-9]*) sectors='' ;; esac",
        "  if [ -n \"\$sectors\" ]; then sz=\$((sectors * 512)); fi",
        "fi",
        "[ -n \"\$sz\" ] || sz=0",
        "echo \"SIZE:\$sz\"",
    )

    private companion object {
        const val TAG = "Partition"
        const val SCAN_TIMEOUT_MILLIS = 60_000L
        const val VERIFY_TIMEOUT_MILLIS = 25_000L

        /**
         * Root scan script. Emits one `name|by-name link|resolved|size` line per
         * partition from every known by-name directory, followed by the active
         * slot and `/proc/mounts`. Values are printed with `printf` so odd names
         * cannot be interpreted by the shell.
         */
        val SCAN_SCRIPT: String = buildString {
            appendLine("for dir in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name; do")
            appendLine("  [ -d \"\$dir\" ] || continue")
            appendLine("  for f in \"\$dir\"/*; do")
            appendLine("    [ -e \"\$f\" ] || continue")
            appendLine("    name=\"\${f##*/}\"")
            appendLine("    resolved=\$(readlink -f \"\$f\" 2>/dev/null)")
            appendLine("    [ -n \"\$resolved\" ] || resolved=\"\$f\"")
            appendLine("    size=''")
            appendLine("    if command -v blockdev >/dev/null 2>&1; then")
            appendLine("      size=\$(blockdev --getsize64 \"\$resolved\" 2>/dev/null)")
            appendLine("    fi")
            appendLine("    case \"\$size\" in ''|*[!0-9]*) size='' ;; esac")
            appendLine("    if [ -z \"\$size\" ] || [ \"\$size\" = \"0\" ]; then")
            appendLine("      dev=\"\${resolved##*/}\"")
            appendLine("      sectors=\$(cat \"/sys/class/block/\$dev/size\" 2>/dev/null)")
            appendLine("      case \"\$sectors\" in ''|*[!0-9]*) sectors='' ;; esac")
            appendLine("      if [ -n \"\$sectors\" ]; then size=\$((sectors * 512)); fi")
            appendLine("    fi")
            appendLine("    [ -n \"\$size\" ] || size=0")
            appendLine("    printf '%s|%s|%s|%s\\n' \"\$name\" \"\$f\" \"\$resolved\" \"\$size\"")
            appendLine("  done")
            appendLine("done")
            appendLine("echo '${PartitionOutputParser.SECTION_PARTITIONS_END}'")
            appendLine("echo '${PartitionOutputParser.SECTION_SLOT}'")
            appendLine("getprop ro.boot.slot_suffix 2>/dev/null")
            appendLine("getprop ro.boot.slot 2>/dev/null")
            appendLine("echo '${PartitionOutputParser.SECTION_MOUNTS}'")
            appendLine("cat /proc/mounts 2>/dev/null || mount 2>/dev/null")
        }.trimEnd()
    }
}

data class TargetCheck(
    val ok: Boolean,
    val message: String,
)
