package com.partflasher.app.core.model

/** Lifecycle of the root privilege check. */
enum class RootState {
    UNKNOWN,
    CHECKING,
    GRANTED,
    DENIED,
    NOT_ROOTED,
    ERROR,
}

/** Root implementation detected on the device. */
enum class RootSolution {
    MAGISK,
    KERNELSU,
    APATCH,
    OTHER,
    NONE,
    UNKNOWN,
}

/**
 * Result of a root detection / request pass.
 *
 * [message] is a short user-facing description, [detail] can contain raw probe
 * output for the log screen.
 */
data class RootStatus(
    val state: RootState = RootState.UNKNOWN,
    val solution: RootSolution = RootSolution.UNKNOWN,
    val version: String? = null,
    val suPath: String? = null,
    val uid: Int? = null,
    val message: String = "",
    val detail: String? = null,
    val checkedAtMillis: Long = 0L,
) {
    val isGranted: Boolean get() = state == RootState.GRANTED

    companion object {
        fun unknown() = RootStatus(state = RootState.UNKNOWN)
        fun checking() = RootStatus(state = RootState.CHECKING, message = "正在申请 Root 权限…")
    }
}
