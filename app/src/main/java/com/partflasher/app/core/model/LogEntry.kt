package com.partflasher.app.core.model

enum class LogLevel { DEBUG, INFO, SUCCESS, WARN, ERROR, COMMAND }

data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)
