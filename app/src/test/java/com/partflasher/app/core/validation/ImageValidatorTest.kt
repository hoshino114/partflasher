package com.partflasher.app.core.validation

import com.partflasher.app.core.model.DeviceInfo
import com.partflasher.app.core.model.ImageFormat
import com.partflasher.app.core.model.ImageMetadata
import com.partflasher.app.core.model.ImageValidation
import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionKind
import com.partflasher.app.core.model.PartitionRisk
import com.partflasher.app.core.model.StagedImage
import com.partflasher.app.core.model.ValidationSeverity
import com.partflasher.app.core.partition.PartitionClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageValidatorTest {

    private val validator = ImageValidator()

    private val mib = 1024L * 1024L

    private fun partition(
        name: String,
        kind: PartitionKind,
        sizeBytes: Long = 256L * mib,
        risk: PartitionRisk = PartitionRisk.NORMAL,
        slotSuffix: String? = null,
        protectedReason: String? = null,
    ): Partition = Partition(
        name = name,
        linkPath = "/dev/block/by-name/$name",
        blockPath = "/dev/block/sda1",
        sizeBytes = sizeBytes,
        kind = kind,
        risk = risk,
        slotSuffix = slotSuffix,
        description = "",
        protectedReason = protectedReason,
    )

    private fun staged(
        format: ImageFormat,
        sizeBytes: Long = 32L * mib,
        displayName: String = "image.img",
    ): StagedImage = StagedImage(
        displayName = displayName,
        sourceUri = null,
        stagedPath = "/cache/staging/$displayName",
        sizeBytes = sizeBytes,
        sha256 = "a".repeat(64),
        metadata = ImageMetadata(format = format, formatLabel = label(format), details = ""),
        stagedAtMillis = 0L,
    )

    private fun label(format: ImageFormat): String = when (format) {
        ImageFormat.ANDROID_BOOT -> "Android boot 镜像"
        ImageFormat.VENDOR_BOOT -> "vendor_boot 镜像"
        ImageFormat.DTBO -> "dtbo 镜像"
        ImageFormat.VBMETA -> "vbmeta 镜像"
        ImageFormat.EXT4 -> "ext4 文件系统镜像"
        ImageFormat.EROFS -> "EROFS 文件系统镜像"
        ImageFormat.SPARSE -> "Android sparse 镜像"
        ImageFormat.GZIP -> "gzip 压缩镜像"
        ImageFormat.LZ4 -> "LZ4 压缩镜像"
        ImageFormat.ZSTD -> "Zstandard 压缩镜像"
        ImageFormat.PAYLOAD -> "Android OTA payload 镜像"
        ImageFormat.UNKNOWN -> "未知格式"
    }

    private fun ImageValidation.codes(): Set<String> = issues.map { it.code }.toSet()

    private fun ImageValidation.hasError(code: String): Boolean =
        errors.any { it.code == code }

    private fun ImageValidation.hasWarning(code: String): Boolean =
        warnings.any { it.code == code }

    // ------------------------------------------------------------------ allowed

    @Test
    fun validBootImageIsAllowed() {
        val result = validator.validate(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
        )
        assertTrue(result.summary, result.canFlash)
        assertTrue(result.errors.isEmpty())
        assertFalse(result.requiresExtraConfirm)
        assertTrue(result.summary.contains("校验通过"))
    }

    @Test
    fun validExt4SystemImageIsAllowed() {
        val result = validator.validate(
            partition("system_a", PartitionKind.SYSTEM, slotSuffix = "_a"),
            staged(ImageFormat.EXT4, displayName = "system.img"),
            activeSlot = "_a",
        )
        assertTrue(result.canFlash)
        assertTrue(result.codes().contains("FORMAT_MATCH_FILESYSTEM"))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun recoveryWithBootImageIsWarningOnly() {
        val result = validator.validate(
            partition("recovery", PartitionKind.RECOVERY),
            staged(ImageFormat.ANDROID_BOOT, displayName = "recovery.img"),
        )
        assertTrue(result.canFlash)
        assertTrue(result.hasWarning("RECOVERY_BOOT_IMAGE"))
        assertTrue(result.issues.first { it.code == "RECOVERY_BOOT_IMAGE" }.message.contains("recovery"))
    }

    @Test
    fun recoveryWithFilesystemImageIsBlocked() {
        val result = validator.validate(
            partition("recovery", PartitionKind.RECOVERY),
            staged(ImageFormat.EXT4, displayName = "recovery.img"),
        )
        assertFalse(result.canFlash)
        assertTrue(result.hasError("FORMAT_MISMATCH_RECOVERY"))
        assertTrue(result.errors.first { it.code == "FORMAT_MISMATCH_RECOVERY" }.message.contains("recovery"))
    }

    // ------------------------------------------------------------------ hard errors

    @Test
    fun protectedPartitionIsBlockedWithReason() {
        val userdata = PartitionClassifier.classify(
            name = "userdata",
            linkPath = "/dev/block/by-name/userdata",
            blockPath = "/dev/block/sda17",
            sizeBytes = 100L * 1024 * mib,
        )
        val result = validator.validate(userdata, staged(ImageFormat.EXT4, displayName = "userdata.img"))
        assertFalse(result.canFlash)
        assertTrue(result.hasError("PROTECTED_PARTITION"))
        val message = result.errors.first { it.code == "PROTECTED_PARTITION" }.message
        assertTrue(message.contains("受保护"))
        assertTrue(message.contains("用户数据"))
    }

    @Test
    fun syntheticCriticalPartitionIsBlockedEvenWithoutRiskFlag() {
        val result = validator.validate(
            partition("persist", PartitionKind.OTHER, risk = PartitionRisk.NORMAL),
            staged(ImageFormat.EXT4),
        )
        assertFalse(result.canFlash)
        assertTrue(result.hasError("PROTECTED_PARTITION"))
    }

    @Test
    fun imageLargerThanPartitionIsBlocked() {
        val result = validator.validate(
            partition("boot", PartitionKind.BOOT, sizeBytes = 8L * mib),
            staged(ImageFormat.ANDROID_BOOT, sizeBytes = 16L * mib, displayName = "boot.img"),
        )
        assertFalse(result.canFlash)
        assertTrue(result.hasError("IMAGE_TOO_LARGE"))
        assertTrue(result.errors.first { it.code == "IMAGE_TOO_LARGE" }.message.contains("超过分区容量"))
    }

    @Test
    fun overlySmallImageIsBlocked() {
        val tooSmall = validator.validate(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.ANDROID_BOOT, sizeBytes = 512L, displayName = "boot.img"),
        )
        assertFalse(tooSmall.canFlash)
        assertTrue(tooSmall.hasError("IMAGE_TOO_SMALL"))

        val empty = validator.validate(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.ANDROID_BOOT, sizeBytes = 0L, displayName = "boot.img"),
        )
        assertFalse(empty.canFlash)
        assertTrue(empty.hasError("IMAGE_EMPTY"))
    }

    @Test
    fun sparseImageIsBlockedWithActionableMessage() {
        val result = validator.validate(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.SPARSE, displayName = "boot.img"),
        )
        assertFalse(result.canFlash)
        val message = result.errors.first { it.code == "UNSUPPORTED_CONTAINER" }.message
        assertTrue(message.contains("sparse"))
        assertTrue(message.contains("simg2img"))
        // The boot-format rule must not produce a redundant second error.
        assertFalse(result.hasError("FORMAT_MISMATCH_BOOT"))
    }

    @Test
    fun compressedAndPayloadImagesAreBlockedWithActionableMessages() {
        val boot = partition("boot", PartitionKind.BOOT)
        assertTrue(validator.validate(boot, staged(ImageFormat.GZIP, displayName = "boot.img"))
            .errors.first { it.code == "UNSUPPORTED_CONTAINER" }.message.contains("解压"))
        assertTrue(validator.validate(boot, staged(ImageFormat.LZ4, displayName = "boot.img"))
            .errors.first { it.code == "UNSUPPORTED_CONTAINER" }.message.contains("解压"))
        assertTrue(validator.validate(boot, staged(ImageFormat.ZSTD, displayName = "boot.img"))
            .errors.first { it.code == "UNSUPPORTED_CONTAINER" }.message.contains("解压"))
        assertTrue(validator.validate(boot, staged(ImageFormat.PAYLOAD, displayName = "payload.bin"))
            .errors.first { it.code == "UNSUPPORTED_CONTAINER" }.message.contains("payload"))
    }

    // ------------------------------------------------------------------ boot-family compatibility

    @Test
    fun bootTargetRequiresAndroidBootImage() {
        val result = validator.validate(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.DTBO, displayName = "dtbo.img"),
        )
        assertFalse(result.canFlash)
        assertTrue(result.hasError("FORMAT_MISMATCH_BOOT"))
        assertTrue(result.errors.first { it.code == "FORMAT_MISMATCH_BOOT" }.message.contains("boot"))
    }

    @Test
    fun vendorBootTargetRequiresVendorBootImage() {
        val result = validator.validate(
            partition("vendor_boot", PartitionKind.VENDOR_BOOT),
            staged(ImageFormat.ANDROID_BOOT, displayName = "vendor_boot.img"),
        )
        assertFalse(result.canFlash)
        assertTrue(result.hasError("FORMAT_MISMATCH_VENDOR_BOOT"))
    }

    @Test
    fun dtboTargetRequiresDtboImage() {
        val result = validator.validate(
            partition("dtbo", PartitionKind.DTBO),
            staged(ImageFormat.ANDROID_BOOT, displayName = "dtbo.img"),
        )
        assertFalse(result.canFlash)
        assertTrue(result.hasError("FORMAT_MISMATCH_DTBO"))
    }

    @Test
    fun vbmetaTargetRequiresVbmetaImage() {
        val result = validator.validate(
            partition("vbmeta", PartitionKind.VBMETA),
            staged(ImageFormat.ANDROID_BOOT, displayName = "vbmeta.img"),
        )
        assertFalse(result.canFlash)
        assertTrue(result.hasError("FORMAT_MISMATCH_VBMETA"))
    }

    @Test
    fun initBootWithNonBootImageIsWarningAndNeedsExtraConfirm() {
        val result = validator.validate(
            partition("init_boot", PartitionKind.INIT_BOOT),
            staged(ImageFormat.EXT4, displayName = "init_boot.img"),
        )
        assertTrue(result.canFlash)
        assertTrue(result.hasWarning("FORMAT_MISMATCH_INIT_BOOT"))
        assertTrue(result.requiresExtraConfirm)
    }

    @Test
    fun initBootWithBootImageIsAllowed() {
        val result = validator.validate(
            partition("init_boot", PartitionKind.INIT_BOOT),
            staged(ImageFormat.ANDROID_BOOT, displayName = "init_boot.img"),
        )
        assertTrue(result.canFlash)
        assertTrue(result.errors.isEmpty())
    }

    // ------------------------------------------------------------------ warnings / extra confirm

    @Test
    fun unknownFormatOnFilesystemTargetNeedsExtraConfirm() {
        val result = validator.validate(
            partition("system", PartitionKind.SYSTEM),
            staged(ImageFormat.UNKNOWN, displayName = "system.img"),
        )
        assertTrue(result.canFlash)
        assertTrue(result.hasWarning("UNKNOWN_FORMAT_FILESYSTEM"))
        assertTrue(result.requiresExtraConfirm)
        assertEquals(1, result.warnings.count { it.code.startsWith("UNKNOWN_FORMAT") })
    }

    @Test
    fun superPartitionNeedsExtraConfirm() {
        val result = validator.validate(
            partition("super", PartitionKind.SUPER, sizeBytes = 8L * 1024 * mib),
            staged(ImageFormat.UNKNOWN, sizeBytes = 1024L * mib, displayName = "super.img"),
        )
        assertTrue(result.canFlash)
        assertTrue(result.hasWarning("SUPER_PARTITION"))
        assertTrue(result.requiresExtraConfirm)
    }

    @Test
    fun firmwarePartitionNeedsExtraConfirm() {
        val result = validator.validate(
            partition("xbl", PartitionKind.FIRMWARE),
            staged(ImageFormat.UNKNOWN, displayName = "xbl.img"),
        )
        assertTrue(result.canFlash)
        assertTrue(result.hasWarning("FIRMWARE_TARGET"))
        assertTrue(result.requiresExtraConfirm)
    }

    @Test
    fun nonActiveSlotIsWarned() {
        val result = validator.validate(
            partition("boot_b", PartitionKind.BOOT, slotSuffix = "_b"),
            staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
            activeSlot = "_a",
        )
        assertTrue(result.canFlash)
        assertTrue(result.hasWarning("NON_ACTIVE_SLOT"))
        assertTrue(result.warnings.first { it.code == "NON_ACTIVE_SLOT" }.message.contains("非活动槽位"))
    }

    @Test
    fun activeSlotMatchDoesNotWarn() {
        val result = validator.validate(
            partition("boot_a", PartitionKind.BOOT, slotSuffix = "_a"),
            staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
            activeSlot = "_a",
        )
        assertTrue(result.canFlash)
        assertFalse(result.codes().contains("NON_ACTIVE_SLOT"))
    }

    @Test
    fun deviceInfoActiveSlotIsUsedWhenNotPassedExplicitly() {
        val result = validator.validate(
            partition("boot_b", PartitionKind.BOOT, slotSuffix = "_b"),
            staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
            activeSlot = null,
            deviceInfo = DeviceInfo(activeSlot = "_a"),
        )
        assertTrue(result.hasWarning("NON_ACTIVE_SLOT"))
    }

    @Test
    fun filenameMismatchProducesWarning() {
        val result = validator.validate(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.ANDROID_BOOT, displayName = "vendor_boot.img"),
        )
        assertTrue(result.canFlash)
        assertTrue(result.hasWarning("FILENAME_MISMATCH"))
        assertTrue(result.warnings.first { it.code == "FILENAME_MISMATCH" }.message.contains("vendor_boot"))
    }

    @Test
    fun filenameMatchDoesNotWarn() {
        val result = validator.validate(
            partition("vendor_boot", PartitionKind.VENDOR_BOOT),
            staged(ImageFormat.VENDOR_BOOT, displayName = "vendor_boot.img"),
        )
        assertTrue(result.canFlash)
        assertFalse(result.codes().contains("FILENAME_MISMATCH"))
    }

    @Test
    fun unknownPartitionSizeIsWarnedAndNeedsExtraConfirm() {
        val result = validator.validate(
            partition("boot", PartitionKind.BOOT, sizeBytes = 0L),
            staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
        )
        assertTrue(result.canFlash)
        assertTrue(result.hasWarning("PARTITION_SIZE_UNKNOWN"))
        assertTrue(result.requiresExtraConfirm)
    }

    // ------------------------------------------------------------------ invariants

    @Test
    fun canFlashIsFalseIfAndOnlyIfAnErrorExists() {
        val cases = listOf(
            validator.validate(
                partition("boot", PartitionKind.BOOT),
                staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
            ),
            validator.validate(
                partition("boot", PartitionKind.BOOT),
                staged(ImageFormat.DTBO, displayName = "boot.img"),
            ),
            validator.validate(
                partition("recovery", PartitionKind.RECOVERY),
                staged(ImageFormat.ANDROID_BOOT, displayName = "recovery.img"),
            ),
            validator.validate(
                PartitionClassifier.classify("metadata", "/dev/block/by-name/metadata", "/dev/block/sda1", mib),
                staged(ImageFormat.EXT4, displayName = "metadata.img"),
            ),
        )
        for (result in cases) {
            assertEquals(result.errors.isEmpty(), result.canFlash)
        }
    }

    @Test
    fun summaryReflectsErrorAndWarningCounts() {
        val failed = validator.validate(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.DTBO, displayName = "dtbo.img"),
        )
        assertTrue(failed.summary.contains("校验未通过"))
        assertTrue(failed.summary.contains("错误"))

        val warned = validator.validate(
            partition("boot_b", PartitionKind.BOOT, slotSuffix = "_b"),
            staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
            activeSlot = "_a",
        )
        assertTrue(warned.summary.contains("注意事项"))
    }

    @Test
    fun everyIssueHasNonBlankChineseMessage() {
        val results = listOf(
            validator.validate(
                partition("boot", PartitionKind.BOOT),
                staged(ImageFormat.DTBO, displayName = "dtbo.img"),
            ),
            validator.validate(
                partition("system", PartitionKind.SYSTEM),
                staged(ImageFormat.UNKNOWN, displayName = "system.img"),
            ),
            validator.validate(
                PartitionClassifier.classify("efs", "/dev/block/by-name/efs", "/dev/block/sda1", mib),
                staged(ImageFormat.EXT4, displayName = "efs.img"),
            ),
        )
        for (result in results) {
            for (issue in result.issues) {
                assertTrue("issue ${issue.code} has empty message", issue.message.isNotBlank())
                assertTrue(
                    "issue ${issue.code} should contain Chinese text: ${issue.message}",
                    issue.message.any { it.code in 0x4E00..0x9FFF },
                )
            }
        }
    }

    @Test
    fun invalidAndValidBothHaveErrorsAsExpected() {
        val invalid = validator.validate(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.SPARSE, displayName = "boot.img"),
        )
        assertEquals(ValidationSeverity.ERROR, invalid.errors.single().severity)
        val valid = validator.validate(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
        )
        assertTrue(valid.errors.isEmpty())
    }
}
