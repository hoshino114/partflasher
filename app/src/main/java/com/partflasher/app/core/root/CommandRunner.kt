package com.partflasher.app.core.root

/** Result of a shell command. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val elapsedMillis: Long,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
    val combinedOutput: String
        get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
    val firstLine: String
        get() = combinedOutput.lineSequence().firstOrNull()?.trim().orEmpty()

    companion object {
        fun failure(message: String): ShellResult = ShellResult(
            exitCode = -1,
            stdout = "",
            stderr = message,
            timedOut = false,
            elapsedMillis = 0L,
        )
    }
}

/**
 * Abstraction over process execution so root/partition logic can be unit tested
 * with a fake runner. Implementations are expected to be safe for IO threads.
 */
interface CommandRunner {
    /**
     * Runs [command] through `sh -c`, or through `su -c` when [asRoot] is true.
     * Never throws for non-zero exit codes; process launch failures are returned
     * as a failure [ShellResult] or thrown as [CommandLaunchException].
     */
    suspend fun run(
        command: String,
        timeoutMillis: Long = 30_000L,
        asRoot: Boolean = false,
    ): ShellResult

    /**
     * Runs a long-lived command and delivers stdout/stderr lines as they arrive.
     * A [timeoutMillis] of 0 disables the timeout. A non-zero exit code is
     * reported through [ShellResult], not thrown.
     */
    suspend fun runStreaming(
        command: String,
        timeoutMillis: Long = 0L,
        asRoot: Boolean = true,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ): ShellResult
}

class CommandLaunchException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
