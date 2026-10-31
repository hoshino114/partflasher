package com.partflasher.app.viewmodel

import com.partflasher.app.core.model.DeviceInfo
import com.partflasher.app.core.model.FlashState
import com.partflasher.app.core.model.ImageValidation
import com.partflasher.app.core.model.LogEntry
import com.partflasher.app.core.model.LogLevel
import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionScanResult
import com.partflasher.app.core.model.RootState
import com.partflasher.app.core.model.RootStatus
import com.partflasher.app.core.model.StagedImage

/** Level filters available on the log screen. */
enum class LogLevelFilter(val levels: Set<LogLevel>?) {
    ALL(null),
    DEBUG(setOf(LogLevel.DEBUG)),
    INFO(setOf(LogLevel.INFO)),
    SUCCESS(setOf(LogLevel.SUCCESS)),
    WARN(setOf(LogLevel.WARN)),
    ERROR(setOf(LogLevel.ERROR)),
    COMMAND(setOf(LogLevel.COMMAND));

    fun matches(entry: LogEntry): Boolean = levels == null || entry.level in levels
}

/**
 * Why the primary flash action is currently disabled. The UI maps every case to
 * a localized explanation with `stringResource`.
 */
enum class FlashBlockReason {
    NONE,

    /** Root access is not granted (or is being checked). */
    ROOT_REQUIRED,

    /** No target partition selected. */
    NO_TARGET,

    /** An image is currently being copied/hashed. */
    IMAGE_STAGING,

    /** No image staged. */
    NO_IMAGE,

    /** Validation is running or has not produced a result yet. */
    VALIDATING,

    /** Validation produced ERROR issues. */
    VALIDATION_ERRORS,

    /** A flash is already running. */
    FLASHING,
}

/** One-shot UI effects, collected by [com.partflasher.app.ui.PartFlasherApp]. */
sealed interface UiEvent {
    data object LogsCopied : UiEvent
    data class LogsExported(val fileName: String) : UiEvent
    data class ExportFailed(val reason: String?) : UiEvent
    data object FlashAlreadyRunning : UiEvent
    data object NotReadyToFlash : UiEvent
    data class FlashFinished(val success: Boolean) : UiEvent
}

/**
 * Immutable snapshot of everything the UI needs. All values are derived from
 * core contracts; computed properties keep the composables free of business
 * rules. The state is intentionally a single data class so configuration
 * changes and process-level operations stay consistent.
 */
data class MainUiState(
    val rootStatus: RootStatus = RootStatus.checking(),
    val deviceInfo: DeviceInfo? = null,
    val deviceLoading: Boolean = false,
    val deviceError: String? = null,
    val scan: PartitionScanResult? = null,
    val scanning: Boolean = false,
    val scanError: String? = null,
    val searchQuery: String = "",
    val selectedPartition: Partition? = null,
    val stagedImage: StagedImage? = null,
    val staging: Boolean = false,
    val stagingProgress: Float = 0f,
    val stagingError: String? = null,
    val validation: ImageValidation? = null,
    val validating: Boolean = false,
    val flashState: FlashState = FlashState.Idle,
    /** Partition name captured when the flash started, so late selection changes
     *  can never mislabel the result dialog. */
    val flashTargetName: String? = null,
    val flashResultVisible: Boolean = false,
    val confirmVisible: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val logFilter: LogLevelFilter = LogLevelFilter.ALL,
    val logQuery: String = "",
) {
    val rootGranted: Boolean get() = rootStatus.isGranted
    val rootChecking: Boolean get() = rootStatus.state == RootState.CHECKING

    val partitions: List<Partition> get() = scan?.partitions.orEmpty()

    /** Slot reported by the scan, falling back to the device info. */
    val activeSlot: String? get() = scan?.activeSlot ?: deviceInfo?.activeSlot

    val isFlashing: Boolean
        get() = when (flashState) {
            is FlashState.Preparing,
            is FlashState.BackingUp,
            is FlashState.Flashing,
            is FlashState.Verifying,
            -> true

            else -> false
        }

    val isTerminalFlashState: Boolean
        get() = flashState is FlashState.Success || flashState is FlashState.Failed

    val isReady: Boolean
        get() = selectedPartition != null && stagedImage != null && validation != null

    val canFlash: Boolean
        get() = rootGranted &&
            !isFlashing &&
            selectedPartition != null &&
            stagedImage != null &&
            validation?.canFlash == true

    /** Human-actionable reason why [canFlash] is false; [FlashBlockReason.NONE] when allowed. */
    val flashBlockReason: FlashBlockReason
        get() = when {
            isFlashing -> FlashBlockReason.FLASHING
            !rootGranted -> FlashBlockReason.ROOT_REQUIRED
            selectedPartition == null -> FlashBlockReason.NO_TARGET
            staging -> FlashBlockReason.IMAGE_STAGING
            stagedImage == null -> FlashBlockReason.NO_IMAGE
            validating -> FlashBlockReason.VALIDATING
            validation == null -> FlashBlockReason.VALIDATING
            validation?.canFlash != true -> FlashBlockReason.VALIDATION_ERRORS
            else -> FlashBlockReason.NONE
        }

    /** Error log count, used for the badge on the log tab. */
    val errorLogCount: Int get() = logs.count { it.level == LogLevel.ERROR }
}
