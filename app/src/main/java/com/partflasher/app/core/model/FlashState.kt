package com.partflasher.app.core.model

/** A flash operation that the user has confirmed. */
data class FlashRequest(
    val partition: Partition,
    val image: StagedImage,
    val createBackup: Boolean,
    val verifyAfterWrite: Boolean,
)

enum class FlashStage {
    IDLE,
    PREPARING,
    BACKING_UP,
    FLASHING,
    VERIFYING,
    SUCCESS,
    FAILED,
}

/** Observable state of the flash controller. */
sealed interface FlashState {
    data object Idle : FlashState

    data class Preparing(val message: String = "正在准备…") : FlashState

    data class BackingUp(
        val progress: Float? = null,
        val message: String = "正在备份原分区…",
    ) : FlashState

    data class Flashing(
        val bytesWritten: Long = 0L,
        val totalBytes: Long = 0L,
        val progress: Float = 0f,
        val bytesPerSecond: Long = 0L,
        val elapsedMillis: Long = 0L,
    ) : FlashState

    data class Verifying(val message: String = "正在校验写入结果…") : FlashState

    data class Success(
        val elapsedMillis: Long = 0L,
        val backupPath: String? = null,
        /** true = hash matches, false = mismatch, null = verification skipped. */
        val verified: Boolean? = null,
        val outputPath: String = "",
    ) : FlashState

    data class Failed(
        val stage: FlashStage,
        val message: String,
        val detail: String? = null,
        val backupPath: String? = null,
    ) : FlashState
}
