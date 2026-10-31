package com.partflasher.app.core.log

import com.partflasher.app.core.model.LogEntry
import com.partflasher.app.core.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Optional persistence for log lines. Implementations must be thread-safe. */
interface LogSink {
    fun append(entry: LogEntry, formattedLine: String)
    fun reset()
}

/**
 * Thread-safe in-memory log with an optional persistent [sink]. Log entries are
 * also meant to be exported by the UI; [snapshotText] renders the full buffer.
 */
class LogRepository(
    private val sink: LogSink? = null,
    private val maxEntries: Int = 3000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var nextId = 1L
    private val buffer = ArrayDeque<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = synchronized(lock) {
            val item = LogEntry(nextId++, clock(), level, tag, message)
            buffer.addLast(item)
            while (buffer.size > maxEntries) buffer.removeFirst()
            _entries.value = buffer.toList()
            item
        }
        runCatching { sink?.append(entry, formatLine(entry)) }
    }

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun success(tag: String, message: String) = log(LogLevel.SUCCESS, tag, message)
    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    /** Logs a command in a copy-pasteable form. */
    fun command(tag: String, command: String) {
        log(LogLevel.COMMAND, tag, "$ $command")
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
        runCatching { sink?.reset() }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    fun snapshotText(): String = snapshot().joinToString("\n") { formatLine(it) }

    companion object {
        fun formatLine(entry: LogEntry): String {
            val time = java.time.Instant.ofEpochMilli(entry.timestampMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
            return "$time ${entry.level.name.padEnd(7)} [${entry.tag}] ${entry.message}"
        }
    }
}
