package com.partflasher.app.core

import com.partflasher.app.core.root.CommandRunner
import com.partflasher.app.core.root.ShellResult

/**
 * Deterministic [CommandRunner] test double: the handler maps a command to a
 * canned [ShellResult]; every invocation is recorded so tests can assert that
 * `su` was (or was not) invoked.
 */
class FakeCommandRunner(
    private val handler: suspend (command: String, timeoutMillis: Long, asRoot: Boolean) -> ShellResult,
) : CommandRunner {

    data class Call(
        val command: String,
        val timeoutMillis: Long,
        val asRoot: Boolean,
    )

    private val lock = Any()
    private val recorded = mutableListOf<Call>()

    val calls: List<Call>
        get() = synchronized(lock) { recorded.toList() }

    val rootCalls: List<Call>
        get() = calls.filter { it.asRoot }

    override suspend fun run(
        command: String,
        timeoutMillis: Long,
        asRoot: Boolean,
    ): ShellResult {
        synchronized(lock) { recorded.add(Call(command, timeoutMillis, asRoot)) }
        return handler(command, timeoutMillis, asRoot)
    }

    override suspend fun runStreaming(
        command: String,
        timeoutMillis: Long,
        asRoot: Boolean,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit,
    ): ShellResult {
        synchronized(lock) { recorded.add(Call(command, timeoutMillis, asRoot)) }
        val result = handler(command, timeoutMillis, asRoot)
        result.stdout.lineSequence().forEach { line -> onStdoutLine(line) }
        result.stderr.lineSequence().forEach { line -> onStderrLine(line) }
        return result
    }
}

object TestShellResults {

    fun ok(
        stdout: String = "",
        stderr: String = "",
        elapsedMillis: Long = 5L,
    ): ShellResult = ShellResult(
        exitCode = 0,
        stdout = stdout,
        stderr = stderr,
        timedOut = false,
        elapsedMillis = elapsedMillis,
    )

    fun failure(
        stderr: String = "",
        exitCode: Int = 1,
        stdout: String = "",
        elapsedMillis: Long = 5L,
    ): ShellResult = ShellResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        timedOut = false,
        elapsedMillis = elapsedMillis,
    )

    fun timeout(
        stdout: String = "",
        stderr: String = "",
        elapsedMillis: Long = 90_000L,
    ): ShellResult = ShellResult(
        exitCode = -1,
        stdout = stdout,
        stderr = stderr,
        timedOut = true,
        elapsedMillis = elapsedMillis,
    )
}
