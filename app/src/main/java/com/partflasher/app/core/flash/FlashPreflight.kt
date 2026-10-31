package com.partflasher.app.core.flash

import com.partflasher.app.core.model.ImageValidation
import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionRisk
import com.partflasher.app.core.model.StagedImage
import com.partflasher.app.core.validation.ImageValidator

/** Outcome of [FlashPreflight.check]. */
data class FlashPreflightResult(
    /** Non-null when the controller must abort before touching the device. */
    val blockedMessage: String?,
    val validation: ImageValidation,
) {
    val blocked: Boolean get() = blockedMessage != null
}

/**
 * All safety rules that must hold *immediately before* a write. Extracted from
 * [FlashController] so the server-side checks can be unit tested without an
 * Android context. The UI's validation is not trusted: this runs again on the
 * exact [Partition] / [StagedImage] pair, including the hard
 * [ImageValidator] rules (protected partitions, boot-family format
 * compatibility, unsupported containers, oversize images).
 */
internal object FlashPreflight {

    fun check(
        partition: Partition,
        image: StagedImage,
        imageValidator: ImageValidator,
        stagedFileExists: Boolean,
        stagedFileSize: Long,
    ): FlashPreflightResult {
        val validation = imageValidator.validate(partition, image)

        // Hard validator errors: protected partition, format/target mismatch,
        // unsupported container, empty/too-small/oversize image.
        if (!validation.canFlash) {
            val messages = validation.errors.map { it.message }
            return FlashPreflightResult(
                blockedMessage = "镜像校验未通过，已阻止刷入：" + messages.joinToString("；"),
                validation = validation,
            )
        }

        // --- Defense in depth: repeat the most critical checks directly.
        if (partition.risk == PartitionRisk.CRITICAL || partition.protectedReason != null) {
            return FlashPreflightResult(
                blockedMessage = "目标分区「${partition.name}」受保护，已阻止写入：" +
                    (partition.protectedReason ?: "该分区属于敏感分区。"),
                validation = validation,
            )
        }
        if (!stagedFileExists) {
            return FlashPreflightResult(
                blockedMessage = "暂存镜像不存在或不可读，请重新选择镜像文件。",
                validation = validation,
            )
        }
        if (image.sizeBytes <= 0L) {
            return FlashPreflightResult(
                blockedMessage = "镜像文件为空（0 字节），已阻止写入。",
                validation = validation,
            )
        }
        if (stagedFileSize != image.sizeBytes) {
            return FlashPreflightResult(
                blockedMessage = "暂存镜像的实际大小（$stagedFileSize 字节）与暂存记录" +
                    "（${image.sizeBytes} 字节）不一致，文件可能已被修改，请重新选择镜像。",
                validation = validation,
            )
        }
        if (partition.sizeBytes > 0L && image.sizeBytes > partition.sizeBytes) {
            return FlashPreflightResult(
                blockedMessage = "镜像大小超过分区容量，已阻止写入。",
                validation = validation,
            )
        }
        return FlashPreflightResult(blockedMessage = null, validation = validation)
    }
}
