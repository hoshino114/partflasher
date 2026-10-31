package com.partflasher.app.core.log

import android.content.Context
import com.partflasher.app.core.model.LogEntry
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only file log sink. Writes one file per day under the app's
 * `files/logs` directory (`session-yyyyMMdd.log`). All methods are thread-safe
 * and never throw: logging must not be able to crash the app.
 */
class FileLogSink(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : LogSink {

    constructor(context: Context) : this(File(context.filesDir, LOG_DIR_NAME))

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var currentDay: String? = null

    override fun append(entry: LogEntry, formattedLine: String) {
        synchronized(lock) {
            runCatching {
                val day = dayStamp(entry.timestampMillis)
                val out = writerFor(day)
                out.write(formattedLine)
                out.newLine()
                out.flush()
            }
        }
    }

    override fun reset() {
        synchronized(lock) {
            runCatching {
                closeWriter()
                val file = File(dir, sessionFileName(clock()))
                dir.mkdirs()
                FileOutputStream(file, false).use { /* truncate the current session file */ }
            }
        }
    }

    /** Current log file for the given timestamp (used by the UI for export). */
    fun currentLogFile(timestampMillis: Long = clock()): File =
        File(dir, sessionFileName(timestampMillis))

    private fun writerFor(day: String): BufferedWriter {
        val existing = writer
        if (existing != null && currentDay == day) return existing
        closeWriter()
        dir.mkdirs()
        val file = File(dir, "session-$day.log")
        val newWriter = BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8),
        )
        writer = newWriter
        currentDay = day
        return newWriter
    }

    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
        currentDay = null
    }

    private fun dayStamp(timestampMillis: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestampMillis))

    private fun sessionFileName(timestampMillis: Long): String = "session-${dayStamp(timestampMillis)}.log"

    companion object {
        const val LOG_DIR_NAME = "logs"
        const val FILE_PREFIX = "session-"
    }
}
