package com.partflasher.app.core.log

import com.partflasher.app.core.model.LogEntry
import com.partflasher.app.core.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLogSinkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dayOne = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private val dayTwo = dayOne + 24L * 60L * 60L * 1000L

    private fun entry(timestamp: Long, message: String, level: LogLevel = LogLevel.INFO): LogEntry =
        LogEntry(id = 1L, timestampMillis = timestamp, level = level, tag = "Test", message = message)

    @Test
    fun appendWritesFormattedLineToSessionFile() {
        val dir = temporaryFolder.newFolder("logs")
        val sink = FileLogSink(dir) { dayOne }
        sink.append(entry(dayOne, "hello world"), "line-1 hello world")
        val file = sink.currentLogFile(dayOne)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("line-1 hello world"))
        assertTrue(file.name.startsWith("session-"))
        assertTrue(file.name.endsWith(".log"))
    }

    @Test
    fun appendsAccumulateWithoutTruncating() {
        val dir = temporaryFolder.newFolder("logs")
        val sink = FileLogSink(dir) { dayOne }
        sink.append(entry(dayOne, "first"), "first")
        sink.append(entry(dayOne, "second"), "second")
        sink.append(entry(dayOne, "third"), "third")
        val lines = sink.currentLogFile(dayOne).readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertEquals("first", lines[0])
        assertEquals("third", lines[2])
    }

    @Test
    fun resetTruncatesCurrentSessionFile() {
        val dir = temporaryFolder.newFolder("logs")
        val sink = FileLogSink(dir) { dayOne }
        sink.append(entry(dayOne, "before reset"), "before reset")
        assertTrue(sink.currentLogFile(dayOne).length() > 0)

        sink.reset()
        assertEquals(0L, sink.currentLogFile(dayOne).length())
    }

    @Test
    fun differentDaysWriteDifferentSessionFiles() {
        val dir = temporaryFolder.newFolder("logs")
        var now = dayOne
        val sink = FileLogSink(dir) { now }
        sink.append(entry(dayOne, "day one"), "day one")
        now = dayTwo
        sink.append(entry(dayTwo, "day two"), "day two")

        val files = dir.listFiles().orEmpty().map { it.name }.toSet()
        assertTrue(files.size >= 2)
        assertTrue(sink.currentLogFile(dayOne).readText().contains("day one"))
        assertTrue(sink.currentLogFile(dayTwo).readText().contains("day two"))
    }

    @Test
    fun logRepositoryPersistsAndClearsThroughSink() {
        val dir = temporaryFolder.newFolder("logs")
        val sink = FileLogSink(dir) { dayOne }
        val repository = LogRepository(sink = sink, clock = { dayOne })
        repository.info("Root", "已获取 Root 权限")
        repository.warn("Flash", "测试警告")

        val file = sink.currentLogFile(dayOne)
        val content = file.readText()
        assertTrue(content.contains("已获取 Root 权限"))
        assertTrue(content.contains("测试警告"))
        assertTrue(content.contains("[Root]"))

        repository.clear()
        assertFalse(sink.currentLogFile(dayOne).readText().contains("已获取 Root 权限"))
        assertTrue(repository.snapshot().isEmpty())
    }

    @Test
    fun appendNeverThrowsForBadDirectory() {
        // A regular file in place of a directory cannot be written to; logging
        // must silently degrade instead of crashing the app.
        val notADir = temporaryFolder.newFile("not-a-dir")
        val sink = FileLogSink(notADir) { dayOne }
        sink.append(entry(dayOne, "should not crash"), "should not crash")
        sink.reset()
    }

    @Test
    fun logRepositoryFormatsLinesWithTimestampLevelAndTag() {
        val repository = LogRepository(clock = { dayOne })
        repository.info("Root", "hello")
        val line = repository.snapshotText()
        assertTrue(line.contains("INFO"))
        assertTrue(line.contains("[Root]"))
        assertTrue(line.contains("hello"))
        assertEquals(1, repository.snapshot().size)
    }

    @Test
    fun repositoryRespectsMaxEntries() {
        val repository = LogRepository(maxEntries = 3)
        repeat(5) { index -> repository.debug("T", "message-$index") }
        val messages = repository.snapshot().map { it.message }
        assertEquals(listOf("message-2", "message-3", "message-4"), messages)
    }
}
