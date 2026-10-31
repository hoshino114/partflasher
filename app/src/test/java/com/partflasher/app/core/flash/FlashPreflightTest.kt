package com.partflasher.app.core.flash

import com.partflasher.app.core.model.ImageFormat
import com.partflasher.app.core.model.ImageMetadata
import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionKind
import com.partflasher.app.core.model.PartitionRisk
import com.partflasher.app.core.model.StagedImage
import com.partflasher.app.core.validation.ImageValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashPreflightTest {

    private val validator = ImageValidator()
    private val mib = 1024L * 1024L

    private fun partition(
        name: String,
        kind: PartitionKind,
        sizeBytes: Long = 256L * mib,
        risk: PartitionRisk = PartitionRisk.CAUTION,
        protectedReason: String? = null,
    ): Partition = Partition(
        name = name,
        linkPath = "/dev/block/by-name/$name",
        blockPath = "/dev/block/sda1",
        sizeBytes = sizeBytes,
        kind = kind,
        risk = risk,
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
        metadata = ImageMetadata(format = format, formatLabel = label(format)),
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

    private fun check(
        partition: Partition,
        image: StagedImage,
        fileExists: Boolean = true,
        fileSize: Long = image.sizeBytes,
    ): FlashPreflightResult = FlashPreflight.check(
        partition = partition,
        image = image,
        imageValidator = validator,
        stagedFileExists = fileExists,
        stagedFileSize = fileSize,
    )

    @Test
    fun compatibleBootImagePasses() {
        val result = check(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
        )
        assertFalse(result.blocked)
        assertNull(result.blockedMessage)
        assertTrue(result.validation.canFlash)
    }

    @Test
    fun vendorBootImageIntoBootPartitionIsBlocked() {
        val result = check(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.VENDOR_BOOT, displayName = "vendor_boot.img"),
        )
        assertTrue(result.blocked)
        assertNotNull(result.blockedMessage)
        assertTrue("message=${result.blockedMessage}", result.blockedMessage.orEmpty().contains("校验未通过"))
        assertTrue(result.validation.errors.any { it.code == "FORMAT_MISMATCH_BOOT" })
    }

    @Test
    fun dtboImageIntoVbmetaPartitionIsBlocked() {
        val result = check(
            partition("vbmeta", PartitionKind.VBMETA),
            staged(ImageFormat.DTBO, displayName = "vbmeta.img"),
        )
        assertTrue(result.blocked)
        assertTrue(result.validation.errors.any { it.code == "FORMAT_MISMATCH_VBMETA" })
    }

    @Test
    fun ext4ImageIntoRecoveryPartitionIsBlocked() {
        val result = check(
            partition("recovery", PartitionKind.RECOVERY),
            staged(ImageFormat.EXT4, displayName = "recovery.img"),
        )
        assertTrue(result.blocked)
        assertTrue(result.validation.errors.any { it.code == "FORMAT_MISMATCH_RECOVERY" })
    }

    @Test
    fun bootImageIntoRecoveryPartitionPassesWithWarning() {
        val result = check(
            partition("recovery", PartitionKind.RECOVERY),
            staged(ImageFormat.ANDROID_BOOT, displayName = "recovery.img"),
        )
        assertFalse(result.blocked)
        assertTrue(result.validation.warnings.any { it.code == "RECOVERY_BOOT_IMAGE" })
    }

    @Test
    fun protectedPartitionIsBlocked() {
        val result = check(
            partition("userdata", PartitionKind.DATA, risk = PartitionRisk.CRITICAL, protectedReason = "用户数据分区：禁止写入。"),
            staged(ImageFormat.EXT4, displayName = "userdata.img"),
        )
        assertTrue(result.blocked)
        assertTrue(result.blockedMessage.orEmpty().contains("校验未通过"))
        assertTrue(result.validation.errors.any { it.code == "PROTECTED_PARTITION" })
    }

    @Test
    fun sparseImageIsBlocked() {
        val result = check(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.SPARSE, displayName = "boot.img"),
        )
        assertTrue(result.blocked)
        assertTrue(result.validation.errors.any { it.code == "UNSUPPORTED_CONTAINER" })
    }

    @Test
    fun oversizeImageIsBlocked() {
        val result = check(
            partition("boot", PartitionKind.BOOT, sizeBytes = 8L * mib),
            staged(ImageFormat.ANDROID_BOOT, sizeBytes = 16L * mib, displayName = "boot.img"),
        )
        assertTrue(result.blocked)
        assertTrue(result.validation.errors.any { it.code == "IMAGE_TOO_LARGE" })
    }

    @Test
    fun missingStagedFileIsBlocked() {
        val result = check(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.ANDROID_BOOT, displayName = "boot.img"),
            fileExists = false,
            fileSize = -1L,
        )
        assertTrue(result.blocked)
        assertTrue(result.blockedMessage.orEmpty().contains("暂存镜像不存在"))
    }

    @Test
    fun tamperedStagedFileSizeIsBlocked() {
        val result = check(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.ANDROID_BOOT, sizeBytes = 32L * mib, displayName = "boot.img"),
            fileExists = true,
            fileSize = 64L * mib,
        )
        assertTrue(result.blocked)
        assertTrue(result.blockedMessage.orEmpty().contains("不一致"))
    }

    @Test
    fun emptyImageIsBlocked() {
        val result = check(
            partition("boot", PartitionKind.BOOT),
            staged(ImageFormat.ANDROID_BOOT, sizeBytes = 0L, displayName = "boot.img"),
            fileExists = true,
            fileSize = 0L,
        )
        assertTrue(result.blocked)
        assertTrue(result.validation.errors.any { it.code == "IMAGE_EMPTY" })
    }

    @Test
    fun warningOnlyCombinationIsNotBlocked() {
        val result = check(
            partition("super", PartitionKind.SUPER, sizeBytes = 8L * 1024 * mib, risk = PartitionRisk.CAUTION),
            staged(ImageFormat.UNKNOWN, sizeBytes = 1024L * mib, displayName = "super.img"),
        )
        assertFalse(result.blocked)
        assertTrue(result.validation.canFlash)
        assertTrue(result.validation.requiresExtraConfirm)
        assertTrue(result.validation.warnings.any { it.code == "SUPER_PARTITION" })
    }
}
