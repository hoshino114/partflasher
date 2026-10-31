package com.partflasher.app.core.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AndroidCommandRunnerTest {

    private val runner = AndroidCommandRunner()

    @Test
    fun runsUnprivilegedCommandAndCapturesStdout() = runBlocking {
        val result = runner.run("echo hello", timeoutMillis = 10_000L)
        assertTrue(result.isSuccess)
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout.trim())
        assertEquals("", result.stderr.trim())
        assertFalse(result.timedOut)
    }

    @Test
    fun reportsNonZeroExitCodeWithoutThrowing() = runBlocking {
        val result = runner.run("echo oops >&2; exit 7", timeoutMillis = 10_000L)
        assertFalse(result.isSuccess)
        assertEquals(7, result.exitCode)
        assertTrue(result.stderr.contains("oops"))
    }

    @Test
    fun separatesStdoutAndStderr() = runBlocking {
        val result = runner.run("echo out; echo err >&2", timeoutMillis = 10_000L)
        assertEquals("out", result.stdout.trim())
        assertEquals("err", result.stderr.trim())
        assertEquals("out\nerr", result.combinedOutput)
    }

    @Test
    fun streamingDeliversLinesAndPreservesPartialFinalLine() = runBlocking {
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        val result = runner.runStreaming(
            command = "printf 'one\\ntwo'; echo three >&2",
            timeoutMillis = 10_000L,
            asRoot = false,
            onStdoutLine = { stdoutLines.add(it) },
            onStderrLine = { stderrLines.add(it) },
        )
        assertTrue(result.isSuccess)
        assertEquals(listOf("one", "two"), stdoutLines)
        assertEquals(listOf("three"), stderrLines)
        assertTrue(result.stdout.contains("one"))
        assertTrue(result.stdout.contains("two"))
    }

    @Test
    fun concurrentOutputOnBothStreamsDoesNotDeadlock() = runBlocking {
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()
        val result = runner.runStreaming(
            command = "i=0; while [ \$i -lt 4000 ]; do echo \"out\$i\"; echo \"err\$i\" >&2; " +
                "i=\$((i+1)); done",
            timeoutMillis = 30_000L,
            asRoot = false,
            onStdoutLine = { synchronized(stdoutLines) { stdoutLines.add(it) } },
            onStderrLine = { synchronized(stderrLines) { stderrLines.add(it) } },
        )
        assertTrue(result.isSuccess)
        assertEquals(4000, stdoutLines.size)
        assertEquals(4000, stderrLines.size)
        assertTrue(result.elapsedMillis < 30_000L)
    }

    @Test
    fun timeoutDestroysProcessAndReportsTimedOut() = runBlocking {
        val started = System.currentTimeMillis()
        val result = runner.run("sleep 30", timeoutMillis = 300L)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue("elapsed=$elapsed should be well under 30s", elapsed < 10_000L)
    }

    @Test
    fun streamingTimeoutAlsoReportsTimedOut() = runBlocking {
        val result = runner.runStreaming(
            command = "sleep 30",
            timeoutMillis = 300L,
            asRoot = false,
        )
        assertTrue(result.timedOut)
        assertFalse(result.isSuccess)
    }

    @Test
    fun missingSuReturnsFailureInsteadOfThrowing() = runBlocking {
        assumeTrue("host has a su binary, cannot test the missing-su path", !hasSu())
        val result = runner.run("id", timeoutMillis = 5_000L, asRoot = true)
        assertFalse(result.isSuccess)
        assertFalse(result.timedOut)
        assertNotEquals(0, result.exitCode)
        assertTrue("stderr should explain the missing su: ${result.stderr}", result.stderr.contains("su"))
    }

    private fun hasSu(): Boolean = runCatching {
        val process = ProcessBuilder("sh", "-c", "command -v su").start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output.isNotEmpty() && File(output).exists()
    }.getOrDefault(false)
}
