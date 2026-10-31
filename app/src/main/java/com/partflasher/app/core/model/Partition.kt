package com.partflasher.app.core.model

/** Semantic kind of a block-device partition. */
enum class PartitionKind {
    BOOT,
    RECOVERY,
    INIT_BOOT,
    VENDOR_BOOT,
    DTBO,
    VBMETA,
    SUPER,
    SYSTEM,
    VENDOR,
    PRODUCT,
    ODM,
    DATA,
    METADATA,
    FIRMWARE,
    MODEM,
    MISC,
    PERSIST,
    OTHER,
}

/** Risk classification used by the confirmation UI. */
enum class PartitionRisk {
    NORMAL,
    CAUTION,
    CRITICAL,
}

/**
 * One entry of the device's by-name partition table.
 *
 * [linkPath] is the symlink inside `/dev/block/by-name` (or a fallback
 * by-name directory), [blockPath] is the resolved block device it points to.
 */
data class Partition(
    val name: String,
    val linkPath: String,
    val blockPath: String,
    val sizeBytes: Long,
    val kind: PartitionKind = PartitionKind.OTHER,
    val slotSuffix: String? = null,
    val description: String = "",
    val risk: PartitionRisk = PartitionRisk.NORMAL,
    val protectedReason: String? = null,
    val isMounted: Boolean = false,
    val mountPoint: String? = null,
) {
    val isProtected: Boolean get() = risk == PartitionRisk.CRITICAL
    val baseName: String get() = slotSuffix?.let { name.removeSuffix(it) } ?: name
    val slotLabel: String
        get() = when (slotSuffix) {
            "_a" -> "A"
            "_b" -> "B"
            else -> ""
        }
    val isBootFamily: Boolean
        get() = kind in setOf(
            PartitionKind.BOOT,
            PartitionKind.RECOVERY,
            PartitionKind.INIT_BOOT,
            PartitionKind.VENDOR_BOOT,
            PartitionKind.DTBO,
            PartitionKind.VBMETA,
        )
}
