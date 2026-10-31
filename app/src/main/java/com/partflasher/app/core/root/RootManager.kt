package com.partflasher.app.core.root

import com.partflasher.app.core.log.LogRepository
import com.partflasher.app.core.model.RootSolution
import com.partflasher.app.core.model.RootState
import com.partflasher.app.core.model.RootStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Detects and requests root access. Must never shell out to `su` unless the user
 * explicitly initiated a root request (the first [detect] call of the session).
 */
class RootManager(
    private val runner: CommandRunner,
    private val log: LogRepository? = null,
) {
    private val mutex = Mutex()

    @Volatile
    private var lastStatus: RootStatus? = null

    /** Most recent detection result, if any. */
    val cachedStatus: RootStatus? get() = lastStatus

    /**
     * Probes for a superuser binary and, when [request] is true, triggers the
     * manager's authorization prompt. Returns a snapshot; never throws.
     */
    suspend fun detect(request: Boolean = true): RootStatus {
        return try {
            mutex.withLock { detectInternal(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = RootStatus(
                state = RootState.ERROR,
                solution = RootSolution.UNKNOWN,
                message = "Root 权限检测失败：${e.message ?: e.javaClass.simpleName}",
                detail = e.stackTraceToString().take(2000),
                checkedAtMillis = System.currentTimeMillis(),
            )
            lastStatus = status
            log?.error(TAG, status.message)
            status
        }
    }

    /** Re-checks an already granted session without showing a new su prompt. */
    suspend fun refresh(): RootStatus {
        return try {
            mutex.withLock { refreshInternal() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = RootStatus(
                state = RootState.ERROR,
                solution = lastStatus?.solution ?: RootSolution.UNKNOWN,
                message = "检查 Root 权限时发生异常：${e.message ?: e.javaClass.simpleName}",
                detail = e.stackTraceToString().take(2000),
                checkedAtMillis = System.currentTimeMillis(),
            )
            lastStatus = status
            log?.error(TAG, status.message)
            status
        }
    }

    /** Throws [RootAccessException] unless root is currently granted. */
    suspend fun ensureRoot(): RootStatus {
        return try {
            mutex.withLock { ensureRootInternal() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RootAccessException) {
            throw e
        } catch (e: Exception) {
            val status = lastStatus ?: RootStatus(
                state = RootState.ERROR,
                message = "Root 权限不可用：${e.message ?: e.javaClass.simpleName}",
                checkedAtMillis = System.currentTimeMillis(),
            )
            log?.error(TAG, "Root 权限校验出错，已中止操作：${status.message}")
            throw RootAccessException(status.message.ifBlank { "Root 权限不可用，已中止操作。" }, status)
        }
    }

    suspend fun run(command: String, timeoutMillis: Long = 30_000L): ShellResult =
        runner.run(command, timeoutMillis, asRoot = true)

    suspend fun runStreaming(
        command: String,
        timeoutMillis: Long = 0L,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ): ShellResult = runner.runStreaming(
        command = command,
        timeoutMillis = timeoutMillis,
        asRoot = true,
        onStdoutLine = onStdoutLine,
        onStderrLine = onStderrLine,
    )

    // ------------------------------------------------------------------ internals

    private suspend fun detectInternal(request: Boolean): RootStatus {
        log?.info(
            TAG,
            if (request) "开始检测 Root 权限（将请求 Root 授权）" else "检测 Root 权限（不请求授权）",
        )

        val probe = runner.run(RootProbeScripts.PROBE_SU, PROBE_TIMEOUT_MILLIS, asRoot = false)
        val suPaths = RootDetectionParser.parseSuPaths(probe.stdout)
        log?.debug(
            TAG,
            "su 探测：exit=${probe.exitCode}，找到 ${suPaths.size} 个候选路径" +
                if (suPaths.isNotEmpty()) "：${suPaths.joinToString()}" else "",
        )

        if (suPaths.isEmpty()) {
            val status = RootStatus(
                state = RootState.NOT_ROOTED,
                solution = RootSolution.NONE,
                message = "未检测到 Root：设备上没有可用的 su。请使用 Magisk、KernelSU 或 APatch 获取 Root 后再试。",
                detail = probeDetail(probe),
                checkedAtMillis = System.currentTimeMillis(),
            )
            lastStatus = status
            log?.warn(TAG, status.message)
            return status
        }

        if (!request) {
            val cached = lastStatus
            if (cached != null && cached.state == RootState.GRANTED) {
                val refreshed = cached.copy(checkedAtMillis = System.currentTimeMillis())
                lastStatus = refreshed
                log?.info(TAG, "使用缓存的 Root 状态：已授权（${RootDetectionParser.solutionLabel(cached.solution)}）")
                return refreshed
            }
            val pending = RootStatus(
                state = RootState.UNKNOWN,
                solution = RootSolution.UNKNOWN,
                suPath = suPaths.first(),
                message = "已检测到 su，但尚未获得 Root 授权，请点击“重新申请 Root 权限”。",
                detail = "su 路径：${suPaths.joinToString()}",
                checkedAtMillis = System.currentTimeMillis(),
            )
            lastStatus = pending
            log?.info(TAG, pending.message)
            return pending
        }

        log?.info(TAG, "正在通过 su 申请 Root 权限（最多 90 秒），请留意手机上的授权弹窗…")
        val result: ShellResult = try {
            runner.run("id", REQUEST_TIMEOUT_MILLIS, asRoot = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShellResult.failure("申请 Root 权限时发生异常：${e.message ?: e.javaClass.simpleName}")
        }
        log?.debug(
            TAG,
            "su 授权结果：exit=${result.exitCode}，timedOut=${result.timedOut}，" +
                "输出=${result.combinedOutput.take(500).replace('\n', ' ')}",
        )

        val uid = RootDetectionParser.parseUid(result.stdout, result.stderr)
        val status: RootStatus = when {
            result.timedOut -> RootStatus(
                state = RootState.ERROR,
                solution = RootSolution.UNKNOWN,
                suPath = suPaths.first(),
                message = "Root 授权超时：请在 90 秒内允许手机 Root 管理器（Magisk / KernelSU / APatch）" +
                    "弹出的授权请求，然后重试。",
                detail = probeDetail(result),
                checkedAtMillis = System.currentTimeMillis(),
            )

            result.isSuccess && uid == 0 -> {
                val manager = detectManager(suPaths)
                val label = RootDetectionParser.solutionLabel(manager.solution)
                val versionSuffix = manager.version?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                RootStatus(
                    state = RootState.GRANTED,
                    solution = manager.solution,
                    version = manager.version,
                    suPath = manager.suPath ?: suPaths.first(),
                    uid = 0,
                    message = "已获取 Root 权限（$label$versionSuffix，uid=0）",
                    detail = manager.detail,
                    checkedAtMillis = System.currentTimeMillis(),
                )
            }

            result.isSuccess -> RootStatus(
                state = RootState.ERROR,
                solution = RootSolution.UNKNOWN,
                suPath = suPaths.first(),
                uid = uid,
                message = "su 已执行，但未确认获得 uid=0（输出：${result.firstLine.take(120)}），请重新授权后再试。",
                detail = probeDetail(result),
                checkedAtMillis = System.currentTimeMillis(),
            )

            RootDetectionParser.classifyFailure(result) == RootState.NOT_ROOTED -> RootStatus(
                state = RootState.NOT_ROOTED,
                solution = RootSolution.NONE,
                suPath = suPaths.first(),
                message = "未检测到可用的 Root：su 无法执行（${result.firstLine.take(120)}）。" +
                    "请检查 Root 管理器是否已正确安装。",
                detail = probeDetail(result),
                checkedAtMillis = System.currentTimeMillis(),
            )

            else -> RootStatus(
                state = RootState.DENIED,
                solution = RootSolution.NONE,
                suPath = suPaths.first(),
                message = "Root 权限被拒绝：请在 Root 管理器（Magisk / KernelSU / APatch）中允许 PartFlasher " +
                    "的授权请求，然后点击重试。",
                detail = probeDetail(result),
                checkedAtMillis = System.currentTimeMillis(),
            )
        }

        lastStatus = status
        if (status.isGranted) {
            log?.success(TAG, status.message)
        } else {
            log?.warn(TAG, "${status.message}${status.detail?.let { d -> "（$d）" }.orEmpty()}")
        }
        return status
    }

    private suspend fun refreshInternal(): RootStatus {
        val cached = lastStatus
        if (cached == null || cached.state != RootState.GRANTED) {
            return detectInternal(request = false)
        }
        log?.info(TAG, "重新检查 Root 权限（静默校验，不主动弹窗）…")
        val result = runner.run("id", VERIFY_TIMEOUT_MILLIS, asRoot = true)
        val uid = RootDetectionParser.parseUid(result.stdout, result.stderr)
        val status = when {
            result.isSuccess && uid == 0 -> cached.copy(
                uid = 0,
                message = "Root 权限正常（${RootDetectionParser.solutionLabel(cached.solution)}）",
                checkedAtMillis = System.currentTimeMillis(),
            )

            result.timedOut -> RootStatus(
                state = RootState.ERROR,
                solution = cached.solution,
                version = cached.version,
                suPath = cached.suPath,
                message = "Root 权限校验超时，请重试。",
                detail = probeDetail(result),
                checkedAtMillis = System.currentTimeMillis(),
            )

            else -> RootStatus(
                state = RootState.DENIED,
                solution = cached.solution,
                version = cached.version,
                suPath = cached.suPath,
                message = "Root 权限已失效或被撤销，请重新申请 Root 授权。",
                detail = probeDetail(result),
                checkedAtMillis = System.currentTimeMillis(),
            )
        }
        lastStatus = status
        if (status.isGranted) log?.info(TAG, status.message) else log?.warn(TAG, status.message)
        return status
    }

    private suspend fun ensureRootInternal(): RootStatus {
        val cached = lastStatus
        val status = when {
            cached != null && cached.state == RootState.GRANTED -> {
                log?.debug(TAG, "刷入前校验 Root 权限…")
                val result = runner.run("id", VERIFY_TIMEOUT_MILLIS, asRoot = true)
                val uid = RootDetectionParser.parseUid(result.stdout, result.stderr)
                when {
                    result.isSuccess && uid == 0 -> cached.copy(
                        uid = 0,
                        message = "Root 权限正常（${RootDetectionParser.solutionLabel(cached.solution)}）",
                        checkedAtMillis = System.currentTimeMillis(),
                    )

                    result.timedOut -> cached.copy(
                        state = RootState.ERROR,
                        message = "刷入前 Root 权限校验超时，已中止操作。",
                        detail = probeDetail(result),
                        checkedAtMillis = System.currentTimeMillis(),
                    )

                    else -> cached.copy(
                        state = RootState.DENIED,
                        message = "Root 权限已失效或被拒绝，已中止操作。请在 Root 管理器中重新授权后重试。",
                        detail = probeDetail(result),
                        checkedAtMillis = System.currentTimeMillis(),
                    )
                }
            }

            cached != null -> cached
            else -> detectInternal(request = false)
        }
        lastStatus = status
        if (!status.isGranted) {
            val message = status.message.ifBlank { "Root 权限不可用，已中止操作。" }
            log?.error(TAG, message)
            throw RootAccessException(message, status)
        }
        log?.debug(TAG, status.message)
        return status
    }

    private suspend fun detectManager(suPaths: List<String>): RootDetectionParser.ManagerInfo {
        val result = try {
            runner.run(RootProbeScripts.MANAGEINFO, MANAGER_TIMEOUT_MILLIS, asRoot = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShellResult.failure("Root 方案探测失败：${e.message ?: e.javaClass.simpleName}")
        }
        if (!result.isSuccess) {
            log?.debug(TAG, "Root 方案探测命令返回 ${result.exitCode}，将使用已获得的信息推断方案。")
        }
        val info = RootDetectionParser.parseManager(result.stdout, suPaths)
        log?.debug(
            TAG,
            "Root 方案：${info.solution}，版本：${info.version ?: "未知"}，su：${info.suPath ?: "未知"}",
        )
        return info
    }

    private fun probeDetail(result: ShellResult): String {
        val output = result.combinedOutput.trim()
        return when {
            output.isEmpty() && result.timedOut -> "命令超时（${result.elapsedMillis} ms）"
            output.isEmpty() -> "退出码 ${result.exitCode}，无输出"
            else -> output.take(4000)
        }
    }

    private companion object {
        const val TAG = "Root"
        const val PROBE_TIMEOUT_MILLIS = 15_000L
        const val REQUEST_TIMEOUT_MILLIS = 90_000L
        const val VERIFY_TIMEOUT_MILLIS = 15_000L
        const val MANAGER_TIMEOUT_MILLIS = 20_000L
    }
}

class RootAccessException(
    message: String,
    val status: RootStatus,
) : Exception(message)
