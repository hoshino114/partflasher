package com.partflasher.app.core.model

/** Detected IMG container / filesystem format. */
enum class ImageFormat {
    ANDROID_BOOT,
    VENDOR_BOOT,
    DTBO,
    VBMETA,
    EXT4,
    EROFS,
    SPARSE,
    GZIP,
    LZ4,
    ZSTD,
    PAYLOAD,
    UNKNOWN,
}

/** Low-level header inspection result. */
data class ImageMetadata(
    val format: ImageFormat = ImageFormat.UNKNOWN,
    val formatLabel: String = "未知格式",
    /** Human readable header details, e.g. "Android boot header v4". */
    val details: String = "",
    /** Expanded (raw) size for Android sparse images, if known. */
    val sparseExpandedBytes: Long? = null,
    val bootHeaderVersion: Int? = null,
)

/**
 * An image that has been copied into app-private staging storage and hashed.
 */
data class StagedImage(
    val displayName: String,
    val sourceUri: String?,
    val stagedPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val metadata: ImageMetadata,
    val stagedAtMillis: Long,
) {
    val format: ImageFormat get() = metadata.format
    val formatLabel: String get() = metadata.formatLabel
    val headerDetails: String get() = metadata.details
}
