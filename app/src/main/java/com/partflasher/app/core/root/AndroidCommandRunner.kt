package com.partflasher.app.core.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * [CommandRunner] backed by [ProcessBuilder].
 *
 * - Unprivileged commands run as `sh -c <command>`.
 * - Root commands run as `su -c <command>`.
 * - stdout and stderr are drained concurrently on IO threads so a process that
 *   writes a lot to one stream can never deadlock on a full pipe.
 * - A timeout destroys the process (and then force-destroys it) and closes its
 *   streams so the reader coroutines always finish.
 * - Missing `su` (or a missing `sh`) is reported as a failure [ShellResult];
 *   non-zero exit codes are never thrown.
 *
 * The command string itself is passed as one argument, so callers must build it
 * with [ShellScriptBuilder] and quote every untrusted value.
 */
class AndroidCommandRunner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CommandRunner {

    override suspend fun run(
        command: String,
        timeoutMillis: Long,
        asRoot: Boolean,
    ): ShellResult = execute(
        command = command,
        timeoutMillis = timeoutMillis,
        asRoot = asRoot,
        onStdoutLine = null,
        onStderrLine = null,
    )

    override suspend fun runStreaming(
        command: String,
        timeoutMillis: Long,
        asRoot: Boolean,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit,
    ): ShellResult = execute(
        command = command,
        timeoutMillis = timeoutMillis,
        asRoot = asRoot,
        onStdoutLine = onStdoutLine,
        onStderrLine = onStderrLine,
    )

    private suspend fun execute(
        command: String,
        timeoutMillis: Long,
        asRoot: Boolean,
        onStdoutLine: ((String) -> Unit)?,
        onStderrLine: ((String) -> Unit)?,
    ): ShellResult = withContext(ioDispatcher) {
        val startedAtNanos = System.nanoTime()
        fun elapsedMillis(): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L

        val argv = if (asRoot) listOf("su", "-c", command) else listOf("sh", "-c", command)
        val process = try {
            ProcessBuilder(argv).start()
        } catch (e: IOException) {
            val message = if (asRoot) {
                "无法启动 su：设备可能未 Root，或 superuser 可执行文件不可用（${e.message ?: e.javaClass.simpleName}）"
            } else {
                "无法启动系统 shell（${e.message ?: e.javaClass.simpleName}）"
            }
            return@withContext ShellResult.failure(message).copy(elapsedMillis = elapsedMillis())
        }

        val stdout = StringBuffer()
        val stderr = StringBuffer()
        var timedOut = false

        // Readers run in their own scope instead of as structured children: when a
        // timed-out process spawned an orphan that keeps the pipe open (e.g. a
        // shell that forked `sleep`), waiting for the reader coroutines could
        // block for the orphan's whole lifetime. The caller must return promptly.
        val readerScope = CoroutineScope(ioDispatcher + SupervisorJob())
        val stdoutJob = readerScope.launch { drain(process.inputStream, stdout, onStdoutLine) }
        val stderrJob = readerScope.launch { drain(process.errorStream, stderr, onStderrLine) }

        try {
            val finished = try {
                runInterruptible {
                    if (timeoutMillis > 0L) {
                        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                    } else {
                        process.waitFor()
                        true
                    }
                }
            } catch (e: InterruptedException) {
                // Cancellation / interrupt: fall through to cleanup below.
                false
            }
            if (finished) {
                // Normal exit: collect the complete output.
                stdoutJob.join()
                stderrJob.join()
            } else {
                timedOut = true
                destroyProcess(process)
                // Best effort: give the readers a short window to see EOF, then
                // abandon them (they are daemons and die with any orphan pipe).
                withTimeoutOrNull(READER_JOIN_TIMEOUT_MILLIS) {
                    stdoutJob.join()
                    stderrJob.join()
                }
                stdoutJob.cancel()
                stderrJob.cancel()
            }
        } catch (e: CancellationException) {
            destroyProcess(process)
            throw e
        } finally {
            if (process.isAlive) destroyProcess(process)
            readerScope.cancel()
            closeQuietly(process)
        }

        val exitCode = if (timedOut) {
            -1
        } else {
            runCatching { process.exitValue() }.getOrDefault(-1)
        }
        ShellResult(
            exitCode = exitCode,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = timedOut,
            elapsedMillis = elapsedMillis(),
        )
    }

    /** Reads [stream] line by line on the current thread, preserving a partial final line. */
    private fun drain(
        stream: InputStream,
        sink: StringBuffer,
        onLine: ((String) -> Unit)?,
    ) {
        try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    sink.append(line).append('\n')
                    onLine?.invoke(line)
                }
            }
        } catch (_: IOException) {
            // The process was destroyed by a timeout/cancellation and the pipe closed.
        }
    }

    /** Destroys the process and closes its streams so blocked readers unblock. */
    private fun destroyProcess(process: Process) {
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(2_000L, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        closeQuietly(process)
    }

    private companion object {
        /** How long a timed-out command's reader coroutines may take to see EOF. */
        const val READER_JOIN_TIMEOUT_MILLIS = 1_000L
    }

    private fun closeQuietly(process: Process) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
}
