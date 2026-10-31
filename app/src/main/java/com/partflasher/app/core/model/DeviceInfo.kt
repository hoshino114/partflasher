package com.partflasher.app.core.model

/** Read-only information about the current device. */
data class DeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val device: String = "",
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    val supportedAbis: List<String> = emptyList(),
    /** "_a", "_b" or null for non-A/B devices. */
    val activeSlot: String? = null,
    val dynamicPartitions: Boolean = false,
    val virtualAb: Boolean = false,
    val isEmulator: Boolean = false,
    val securityPatch: String? = null,
    val kernelVersion: String? = null,
    val buildFingerprint: String? = null,
) {
    val displayName: String
        get() = listOf(manufacturer, model).filter { it.isNotBlank() }.distinct().joinToString(" ")

    val slotLabel: String
        get() = when (activeSlot) {
            "_a" -> "A"
            "_b" -> "B"
            else -> "无 (非 A/B)"
        }

    val isArm64: Boolean
        get() = supportedAbis.any { it == "arm64-v8a" }
}
