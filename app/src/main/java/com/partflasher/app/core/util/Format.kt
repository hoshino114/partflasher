package com.partflasher.app.core.util

import java.util.Locale

/** Human-readable formatting helpers shared by validation, flash and log output. */
object Format {

    private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

    /** Formats a byte count, e.g. `1.50 MiB`. */
    fun bytes(size: Long): String {
        if (size < 0L) return "未知"
        if (size < 1024L) return "$size B"
        var value = size.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < UNITS.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.2f %s", value, UNITS[unit])
    }

    /** Formats a duration in milliseconds, e.g. `1 分 05 秒`. */
    fun duration(millis: Long): String {
        if (millis < 0L) return "未知"
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours} 小时 ${minutes} 分 ${seconds} 秒"
            minutes > 0L -> "${minutes} 分 ${seconds} 秒"
            else -> "${seconds} 秒"
        }
    }

    /** Formats a transfer speed, e.g. `18.42 MiB/s`. */
    fun speed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0L) "0 B/s" else "${bytes(bytesPerSecond)}/s"
}
