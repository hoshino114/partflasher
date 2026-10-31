package com.partflasher.app.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure formatting helpers shared by the UI. These intentionally contain no
 * user-facing Chinese copy: labels live in `strings.xml` and are composed with
 * `stringResource(...)`; here we only render numbers, units and times.
 */
object Formats {

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** 1.5 KB / 64.0 MB / 3.2 GB — binary units, always one decimal for MB+. */
    fun bytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    /** Transfer speed, e.g. 24.3 MB/s. */
    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "—"
        return bytes(bytesPerSecond) + "/s"
    }

    /** 00:42 or 1:02:03. */
    fun duration(millis: Long): String {
        if (millis <= 0L) return "00:00"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /** Wall-clock time for a log line. */
    fun logTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormatter)

    /** Full date-time for the root check timestamp; empty when unknown. */
    fun dateTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return ""
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
    }

    /** Suggested SAF export file name, e.g. partflasher-log-20250131-2042.txt. */
    fun logFileName(epochMillis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        val stamp = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter)
        return "partflasher-log-$stamp.txt"
    }

    /** Percentage 0–100, clamped. */
    fun percent(progress: Float): Int = (progress.coerceIn(0f, 1f) * 100).toInt()
}
