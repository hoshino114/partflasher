package com.partflasher.app.core.flash

import android.content.Context
import android.os.PowerManager
import com.partflasher.app.core.log.LogRepository
import com.partflasher.app.core.model.FlashRequest
import com.partflasher.app.core.model.FlashStage
import com.partflasher.app.core.model.FlashState
import com.partflasher.app.core.partition.PartitionRepository
import com.partflasher.app.core.root.RootAccessException
import com.partflasher.app.core.root.RootManager
import com.partflasher.app.core.util.Format
import com.partflasher.app.core.validation.ImageValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executes a confirmed flash: pre-flight checks, optional backup, chunked write,
 * sync, optional read-back verification and cleanup. State is shared through
 * [state] so a configuration change does not interrupt an operation.
 *
 * Safety rules enforced here (in addition to the UI/validator):
 *  - the full [ImageValidator] rule set is re-run on the exact partition/image
 *    pair immediately before anything is written: protected partitions,
 *    boot-family format compatibility, unsupported containers and oversize
 *    images are all hard-blocked,
 *  - the target is re-resolved and re-checked immediately before writing,
 *  - root is re-verified before any write,
 *  - backups larger than [FlashScriptBuilder.MAX_BACKUP_BYTES] are refused,
 *  - a failed backup aborts before the write,
 *  - writes are never retried and cannot be cancelled from the UI during the
 *    write phase (there is deliberately no cancel API).
 */
class FlashController(
    private val context: Context,
    private val rootManager: RootManager,
    private val partitionRepository: PartitionRepository,
    private val log: LogRepository,
    private val imageValidator: ImageValidator = ImageValidator(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<FlashState>(FlashState.Idle)
    val state: StateFlow<FlashState> = _state.asStateFlow()

    private val lock = Any()
    private var running = false
    private var operationJob: Job? = null

    val isRunning: Boolean
        get() = _state.value !is FlashState.Idle &&
            _state.value !is FlashState.Success &&
            _state.value !is FlashState.Failed

    /** Returns false when another operation is already running. */
    fun start(request: FlashRequest): Boolean {
        synchronized(lock) {
            if (running) {
                log.warn(TAG, "已有刷入任务正在执行，忽略新的刷入请求。")
                return false
            }
            running = true
            _state.value = FlashState.Preparing()
            val job = scope.launch { execute(request) }
            operationJob = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    running = false
                    if (operationJob === job) operationJob = null
                }
            }
            return true
        }
    }

    /** Clears a terminal success/failure state. */
    fun reset() {
        if (!isRunning) _state.value = FlashState.Idle
    }

    fun shutdown() {
        synchronized(lock) {
            operationJob?.cancel()
        }
    }

    // ------------------------------------------------------------------ execution

    private suspend fun execute(request: FlashRequest) {
        val startedAtMillis = System.currentTimeMillis()
        var stage = FlashStage.PREPARING
        var backupPath: String? = null
        var remoteImagePath: String? = null
        var writeStarted = false
        val wakeLock = acquireWakeLock()

        try {
            log.info(
                TAG,
                "开始刷入：分区=${request.partition.name}（${request.partition.blockPath}），" +
                    "镜像=${request.image.displayName}（${Format.bytes(request.image.sizeBytes)}，" +
                    "SHA-256=${request.image.sha256}），备份=${if (request.createBackup) "是" else "否"}，" +
                    "写入后校验=${if (request.verifyAfterWrite) "是" else "否"}",
            )

            // ---- 1. Server-side safety re-checks (never trust the UI).
            // Re-runs the full ImageValidator matrix on the exact partition/image
            // pair: protected partitions, boot-family format compatibility,
            // unsupported containers and oversize/empty images are hard-blocked.
            val stagedFile = File(request.image.stagedPath)
            val preflight = FlashPreflight.check(
                partition = request.partition,
                image = request.image,
                imageValidator = imageValidator,
                stagedFileExists = stagedFile.isFile,
                stagedFileSize = if (stagedFile.isFile) stagedFile.length() else -1L,
            )
            if (preflight.blockedMessage != null) {
                fail(stage, preflight.blockedMessage)
                return
            }
            if (preflight.validation.requiresExtraConfirm) {
                log.warn(
                    TAG,
                    "本次刷入存在需要额外确认的风险项：" +
                        preflight.validation.warnings.joinToString("；") { it.message },
                )
            }

            // ---- 2. Re-resolve the target device node.
            val check = partitionRepository.verifyTarget(request.partition)
            if (!check.ok) {
                fail(stage, check.message)
                return
            }
            log.info(TAG, check.message)

            // ---- 3. Re-verify root before touching the device.
            val rootStatus = try {
                rootManager.ensureRoot()
            } catch (e: RootAccessException) {
                fail(stage, "Root 权限不可用，已中止刷入：${e.message ?: "请重新授权 Root"}")
                return
            }
            log.info(TAG, "Root 权限正常：${rootStatus.message}")

            // ---- 4. Backups bigger than the limit are refused before any write.
            val backupSize = request.partition.sizeBytes
            if (request.createBackup) {
                when {
                    backupSize <= 0L -> {
                        fail(stage, "无法读取目标分区大小，无法安全备份，已按安全策略中止。可关闭备份后重试。")
                        return
                    }

                    backupSize > FlashScriptBuilder.MAX_BACKUP_BYTES -> {
                        fail(
                            stage,
                            "分区大小（${Format.bytes(backupSize)}）超过备份上限" +
                                "（${Format.bytes(FlashScriptBuilder.MAX_BACKUP_BYTES)}），" +
                                "为保护设备存储已中止。请关闭备份后重试。",
                        )
                        return
                    }
                }
            }
            val requiredRootSpace = request.image.sizeBytes +
                (if (request.createBackup) backupSize else 0L) +
                ROOT_SPACE_MARGIN_BYTES
            val freeSpaceResult = rootManager.run(FlashScriptBuilder.checkFreeSpaceScript(), SHORT_TIMEOUT_MILLIS)
            val availableKb = FlashScriptBuilder.parseFreeSpaceKb(freeSpaceResult.stdout)
            if (availableKb != null && availableKb * 1024L < requiredRootSpace) {
                fail(
                    stage,
                    "设备 /data 存储空间不足：需要约 ${Format.bytes(requiredRootSpace)}，" +
                        "当前可用约 ${Format.bytes(availableKb * 1024L)}。请清理空间后重试。",
                )
                return
            }
            if (availableKb == null) {
                log.warn(TAG, "无法读取 /data/local/tmp 可用空间，跳过空间检查。")
            }

            // ---- 5. Copy the staged image to a root-owned path (SELinux safety).
            remoteImagePath = FlashScriptBuilder.remoteImagePath(stagedFile.name, startedAtMillis)
            val copyScript = FlashScriptBuilder.copyToRootScript(
                localPath = request.image.stagedPath,
                remotePath = remoteImagePath,
                expectedBytes = request.image.sizeBytes,
            )
            log.command(TAG, copyScript)
            val copyResult = rootManager.run(copyScript, COPY_TIMEOUT_MILLIS)
            val copiedBytes = FlashScriptBuilder.parseCopiedBytes(copyResult.stdout)
            if (!copyResult.isSuccess || copiedBytes != request.image.sizeBytes) {
                fail(
                    stage,
                    "复制镜像到临时目录失败，已中止刷入（未写入任何数据）。",
                    detail = copyResult.combinedOutput.take(DETAIL_LIMIT),
                )
                return
            }
            log.success(TAG, "镜像已复制到 $remoteImagePath（${Format.bytes(copiedBytes)}）")

            // ---- 6. Optional backup (must succeed before writing).
            if (request.createBackup) {
                stage = FlashStage.BACKING_UP
                val targetBackupPath = FlashScriptBuilder.backupPath(request.partition.name, startedAtMillis)
                _state.value = FlashState.BackingUp(progress = 0f)
                log.info(TAG, "开始备份分区 ${request.partition.name}（${Format.bytes(backupSize)}）到 $targetBackupPath …")
                val backupScript = FlashScriptBuilder.backupScript(
                    partitionPath = request.partition.blockPath,
                    backupPath = targetBackupPath,
                    totalBytes = backupSize,
                )
                log.command(TAG, backupScript)
                val backupResult = rootManager.runStreaming(
                    command = backupScript,
                    timeoutMillis = BACKUP_TIMEOUT_MILLIS,
                    onStdoutLine = { line ->
                        FlashScriptBuilder.parseProgressLine(line)?.let { progress ->
                            _state.value = FlashState.BackingUp(
                                progress = progress.fraction,
                                message = "正在备份原分区…（${Format.bytes(progress.written)} / " +
                                    "${Format.bytes(progress.total)}）",
                            )
                        }
                    },
                    onStderrLine = { line -> if (line.isNotBlank()) log.debug(TAG, "备份 dd: $line") },
                )
                val backedUpBytes = FlashScriptBuilder.parseBackupBytes(backupResult.stdout)
                if (!backupResult.isSuccess || backedUpBytes != backupSize) {
                    fail(
                        stage,
                        "备份原分区失败，已中止刷入（未写入任何数据）。" +
                            "请检查 /data 剩余空间后重试，或关闭备份。",
                        detail = backupResult.combinedOutput.take(DETAIL_LIMIT),
                    )
                    return
                }
                backupPath = targetBackupPath
                log.success(TAG, "备份完成：$targetBackupPath（${Format.bytes(backedUpBytes)}）")
            }

            // ---- 7. Chunked write with progress.
            stage = FlashStage.FLASHING
            writeStarted = true
            val totalBytes = request.image.sizeBytes
            val writeStartedNanos = System.nanoTime()
            _state.value = FlashState.Flashing(bytesWritten = 0L, totalBytes = totalBytes, progress = 0f)
            log.info(TAG, "开始写入分区 ${request.partition.name}（${Format.bytes(totalBytes)}，4 MiB 分块）…")
            val flashScript = FlashScriptBuilder.flashScript(
                remoteImagePath = remoteImagePath,
                partitionPath = request.partition.blockPath,
                totalBytes = totalBytes,
            )
            log.command(TAG, flashScript)
            val flashResult = rootManager.runStreaming(
                command = flashScript,
                timeoutMillis = 0L,
                onStdoutLine = { line ->
                    FlashScriptBuilder.parseProgressLine(line)?.let { progress ->
                        val elapsedMillis = (System.nanoTime() - writeStartedNanos) / 1_000_000L
                        val speed = if (elapsedMillis > 0L) {
                            progress.written * 1000L / elapsedMillis
                        } else {
                            0L
                        }
                        _state.value = FlashState.Flashing(
                            bytesWritten = progress.written,
                            totalBytes = progress.total,
                            progress = progress.fraction,
                            bytesPerSecond = speed,
                            elapsedMillis = elapsedMillis,
                        )
                    }
                },
                onStderrLine = { line -> if (line.isNotBlank()) log.debug(TAG, "写入 dd: $line") },
            )
            if (!flashResult.isSuccess) {
                fail(
                    stage,
                    "写入分区失败（退出码 ${flashResult.exitCode}）。设备上的分区可能处于不完整状态，" +
                        "请使用备份恢复或重新刷入完整的官方镜像。",
                    detail = flashResult.combinedOutput.take(DETAIL_LIMIT),
                    backupPath = backupPath,
                )
                return
            }
            if (flashResult.timedOut) {
                fail(
                    stage,
                    "写入分区超时，分区可能处于不完整状态，请使用备份恢复或重新刷入完整镜像。",
                    detail = flashResult.combinedOutput.take(DETAIL_LIMIT),
                    backupPath = backupPath,
                )
                return
            }
            log.success(TAG, "分区写入完成：${Format.bytes(totalBytes)}")
            _state.value = FlashState.Flashing(
                bytesWritten = totalBytes,
                totalBytes = totalBytes,
                progress = 1f,
                elapsedMillis = (System.nanoTime() - writeStartedNanos) / 1_000_000L,
            )

            // ---- 8. sync, then optional read-back verification.
            val syncResult = rootManager.run(FlashScriptBuilder.syncScript(), SYNC_TIMEOUT_MILLIS)
            if (!syncResult.isSuccess) {
                fail(
                    stage,
                    "写入后执行 sync 失败，无法确认数据已落盘。请使用备份恢复或重新刷入完整镜像。",
                    detail = syncResult.combinedOutput.take(DETAIL_LIMIT),
                    backupPath = backupPath,
                )
                return
            }
            log.info(TAG, "sync 完成。")

            var verified: Boolean? = null
            if (request.verifyAfterWrite) {
                stage = FlashStage.VERIFYING
                _state.value = FlashState.Verifying()
                log.info(TAG, "正在读取分区内容进行 SHA-256 校验…")
                val verifyScript = FlashScriptBuilder.verifyScript(
                    partitionPath = request.partition.blockPath,
                    sizeBytes = totalBytes,
                    expectedSha256 = request.image.sha256,
                )
                log.command(TAG, verifyScript)
                val verifyResult = rootManager.run(verifyScript, VERIFY_TIMEOUT_MILLIS)
                if (FlashScriptBuilder.isVerifyUnsupported(verifyResult)) {
                    // The device has no sha256sum binary: the write already
                    // succeeded, so skip verification instead of failing the flash.
                    log.warn(
                        TAG,
                        "设备缺少 sha256sum 命令，无法执行写入后 SHA-256 校验；" +
                            "写入已完成，本次校验已跳过（无法确认写入内容）。",
                    )
                    verified = null
                } else {
                    val actualSha = FlashScriptBuilder.parseSha256(verifyResult.stdout)
                    if (actualSha == null) {
                        fail(
                            stage,
                            "无法读取写入结果进行校验。写入已完成，但校验未通过，请使用备份恢复或重新刷入完整镜像。",
                            detail = verifyResult.combinedOutput.take(DETAIL_LIMIT),
                            backupPath = backupPath,
                        )
                        return
                    }
                    if (!actualSha.equals(request.image.sha256, ignoreCase = true)) {
                        fail(
                            stage,
                            "写入校验失败：分区内容 SHA-256 与镜像不一致，请使用备份恢复或重新刷入完整镜像。",
                            detail = "期望：${request.image.sha256}\n实际：$actualSha",
                            backupPath = backupPath,
                        )
                        return
                    }
                    verified = true
                    log.success(TAG, "写入校验通过：SHA-256 一致（$actualSha）")
                }
            }

            // ---- 9. Success.
            val elapsedMillis = System.currentTimeMillis() - startedAtMillis
            _state.value = FlashState.Success(
                elapsedMillis = elapsedMillis,
                backupPath = backupPath,
                verified = verified,
                outputPath = request.partition.blockPath,
            )
            log.success(
                TAG,
                "刷入成功：${request.partition.name}，耗时 ${Format.duration(elapsedMillis)}" +
                    (backupPath?.let { "，备份：$it" } ?: ""),
            )
        } catch (e: CancellationException) {
            log.warn(TAG, "刷入任务被取消（应用退出）。")
            if (writeStarted && _state.value !is FlashState.Success && _state.value !is FlashState.Failed) {
                fail(
                    stage,
                    "刷入任务被意外中断，分区可能处于不完整状态，请使用备份恢复或重新刷入完整镜像。",
                    backupPath = backupPath,
                )
            }
            throw e
        } catch (e: Exception) {
            log.error(TAG, "刷入过程中发生异常：${e.message}")
            if (_state.value !is FlashState.Success && _state.value !is FlashState.Failed) {
                fail(
                    stage,
                    "刷入过程中发生异常：${e.message ?: e.javaClass.simpleName}",
                    detail = e.stackTraceToString().take(DETAIL_LIMIT),
                    backupPath = backupPath,
                )
            }
        } finally {
            releaseWakeLock(wakeLock)
            // Clean the root-staged image; backups are intentionally kept.
            val path = remoteImagePath
            if (path != null) {
                withContext(NonCancellable) {
                    runCatching {
                        val cleanup = rootManager.run(
                            FlashScriptBuilder.cleanupScript(path),
                            SHORT_TIMEOUT_MILLIS,
                        )
                        if (cleanup.isSuccess) {
                            log.debug(TAG, "已清理临时镜像：$path")
                        } else {
                            log.warn(TAG, "清理临时镜像失败：$path")
                        }
                    }.onFailure { log.warn(TAG, "清理临时镜像异常：${it.message}") }
                }
            }
        }
    }

    private fun fail(
        stage: FlashStage,
        message: String,
        detail: String? = null,
        backupPath: String? = null,
    ) {
        _state.value = FlashState.Failed(
            stage = stage,
            message = message,
            detail = detail,
            backupPath = backupPath,
        )
        val suffix = buildString {
            if (!detail.isNullOrBlank()) append("\n详情：").append(detail)
            if (!backupPath.isNullOrBlank()) append("\n备份路径：").append(backupPath)
        }
        log.error(TAG, "刷入失败（阶段 $stage）：$message$suffix")
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    } catch (e: Exception) {
        log.warn(TAG, "无法获取唤醒锁，刷入过程中设备可能休眠：${e.message}")
        null
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        runCatching {
            if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
        }
    }

    private companion object {
        const val TAG = "Flash"
        const val DETAIL_LIMIT = 4000
        const val SHORT_TIMEOUT_MILLIS = 30_000L
        const val COPY_TIMEOUT_MILLIS = 10L * 60L * 1000L
        const val BACKUP_TIMEOUT_MILLIS = 15L * 60L * 1000L
        const val SYNC_TIMEOUT_MILLIS = 120_000L
        const val VERIFY_TIMEOUT_MILLIS = 10L * 60L * 1000L
        const val ROOT_SPACE_MARGIN_BYTES = 16L * 1024L * 1024L
        const val WAKE_LOCK_TAG = "PartFlasher:flash"
        const val WAKE_LOCK_TIMEOUT_MILLIS = 60L * 60L * 1000L
    }
}
