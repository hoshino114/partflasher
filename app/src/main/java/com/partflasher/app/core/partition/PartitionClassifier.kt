package com.partflasher.app.core.partition

import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionKind
import com.partflasher.app.core.model.PartitionRisk

/**
 * Pure classification of raw by-name block devices into [Partition] models:
 * kind, slot suffix, Chinese description and flash-risk level.
 */
object PartitionClassifier {

    /**
     * Partitions that must never be written by the app (safety invariant #2).
     * Keyed by base partition name (without `_a`/`_b` suffix).
     */
    val PROTECTED_REASONS: Map<String, String> = linkedMapOf(
        "userdata" to "用户数据分区（userdata）：写入会清空或损坏全部应用与用户数据，通常无法恢复。",
        "data" to "用户数据分区（data）：写入会清空或损坏全部应用与用户数据，通常无法恢复。",
        "metadata" to "元数据分区（metadata）：写入会导致数据加密/更新状态损坏，可能导致设备无法开机。",
        "persist" to "校准数据分区（persist）：写入会导致传感器、网络、指纹等校准数据丢失，通常不可恢复。",
        "frp" to "恢复出厂设置保护分区（frp）：随意写入可能导致恢复出厂设置保护功能异常。",
        "devinfo" to "设备信息分区（devinfo）：写入可能导致设备信息丢失或部分功能异常。",
        "keystore" to "密钥库分区（keystore）：写入会导致应用密钥、证书丢失且无法恢复。",
        "misc" to "启动控制分区（misc）：写入可能导致设备无法正常开机或进入错误的启动模式。",
        "ssd" to "存储状态分区（ssd）：写入可能导致存储状态异常，影响系统启动。",
        "modemst1" to "基带校准分区（modemst1）：写入可能导致无法通话、无法联网。",
        "modemst2" to "基带校准分区（modemst2）：写入可能导致无法通话、无法联网。",
        "fsg" to "基带备份分区（fsg）：写入可能损坏基带数据，导致无法通话/联网。",
        "efs" to "网络与 IMEI 数据分区（efs）：写入可能导致信号丢失、IMEI 损坏且不可恢复。",
    )

    private val FIRMWARE_PREFIXES = listOf(
        "xbl", "abl", "tz", "hyp", "aop", "devcfg", "uefi", "keymaster",
        "cmnlib", "mdtp", "qupfw", "bootloader", "dsp", "bluetooth",
    )

    /** `_a` / `_b` slot suffix, or null. */
    fun slotSuffix(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.endsWith("_a") && name.length > 2 -> "_a"
            lower.endsWith("_b") && name.length > 2 -> "_b"
            else -> null
        }
    }

    /** Partition name without its slot suffix. */
    fun baseName(name: String): String = slotSuffix(name)?.let { name.dropLast(it.length) } ?: name

    fun isProtectedBase(baseName: String): Boolean =
        PROTECTED_REASONS.containsKey(baseName.lowercase())

    fun protectedReason(baseName: String): String? =
        PROTECTED_REASONS[baseName.lowercase()]

    fun kindOf(baseName: String): PartitionKind {
        val name = baseName.lowercase()
        return when {
            name == "boot" -> PartitionKind.BOOT
            name == "init_boot" -> PartitionKind.INIT_BOOT
            name == "vendor_boot" -> PartitionKind.VENDOR_BOOT
            name == "recovery" -> PartitionKind.RECOVERY
            name == "dtbo" -> PartitionKind.DTBO
            name.startsWith("vbmeta") -> PartitionKind.VBMETA
            name == "super" -> PartitionKind.SUPER
            name in setOf("system", "system_ext", "system_dlkm", "system_other") -> PartitionKind.SYSTEM
            name in setOf("vendor", "vendor_dlkm") -> PartitionKind.VENDOR
            name == "product" -> PartitionKind.PRODUCT
            name in setOf("odm", "odm_dlkm") -> PartitionKind.ODM
            name in setOf("userdata", "data") -> PartitionKind.DATA
            name == "metadata" -> PartitionKind.METADATA
            name == "misc" -> PartitionKind.MISC
            name == "persist" -> PartitionKind.PERSIST
            name == "modem" || name.startsWith("modemst") || name == "fsg" -> PartitionKind.MODEM
            FIRMWARE_PREFIXES.any { name.startsWith(it) } -> PartitionKind.FIRMWARE
            else -> PartitionKind.OTHER
        }
    }

    fun riskOf(kind: PartitionKind, baseName: String): PartitionRisk = when {
        isProtectedBase(baseName) -> PartitionRisk.CRITICAL
        kind in setOf(
            PartitionKind.BOOT,
            PartitionKind.RECOVERY,
            PartitionKind.INIT_BOOT,
            PartitionKind.VENDOR_BOOT,
            PartitionKind.DTBO,
            PartitionKind.VBMETA,
            PartitionKind.SUPER,
            PartitionKind.FIRMWARE,
            PartitionKind.MODEM,
        ) -> PartitionRisk.CAUTION
        else -> PartitionRisk.NORMAL
    }

    fun descriptionOf(kind: PartitionKind): String = when (kind) {
        PartitionKind.BOOT -> "内核与初始内存盘分区（Android boot 镜像，直接决定设备能否启动）"
        PartitionKind.RECOVERY -> "恢复模式分区（recovery 镜像）"
        PartitionKind.INIT_BOOT -> "初始启动分区（init_boot 镜像，包含通用 ramdisk）"
        PartitionKind.VENDOR_BOOT -> "厂商启动分区（vendor_boot 镜像，包含厂商 ramdisk/DTB）"
        PartitionKind.DTBO -> "设备树覆盖分区（dtbo 镜像）"
        PartitionKind.VBMETA -> "AVB 校验元数据分区（vbmeta 镜像）"
        PartitionKind.SUPER -> "动态分区容器（super，包含 system/vendor/product 等逻辑分区）"
        PartitionKind.SYSTEM -> "系统分区（system 文件系统镜像）"
        PartitionKind.VENDOR -> "厂商分区（vendor 文件系统镜像）"
        PartitionKind.PRODUCT -> "产品分区（product 文件系统镜像）"
        PartitionKind.ODM -> "ODM 分区（odm 文件系统镜像）"
        PartitionKind.DATA -> "用户数据分区"
        PartitionKind.METADATA -> "元数据与加密状态分区"
        PartitionKind.FIRMWARE -> "底层固件/引导加载分区（刷写错误可能导致设备无法开机）"
        PartitionKind.MODEM -> "基带与调制解调器相关分区"
        PartitionKind.MISC -> "启动控制杂项分区"
        PartitionKind.PERSIST -> "持久化校准数据分区"
        PartitionKind.OTHER -> "其他分区"
    }

    /** Sort priority: common boot-family partitions first, then the rest. */
    fun sortOrder(kind: PartitionKind): Int = when (kind) {
        PartitionKind.BOOT -> 0
        PartitionKind.INIT_BOOT -> 1
        PartitionKind.VENDOR_BOOT -> 2
        PartitionKind.DTBO -> 3
        PartitionKind.VBMETA -> 4
        PartitionKind.RECOVERY -> 5
        PartitionKind.SUPER -> 6
        else -> 100
    }

    fun classify(
        name: String,
        linkPath: String,
        blockPath: String,
        sizeBytes: Long,
        isMounted: Boolean = false,
        mountPoint: String? = null,
    ): Partition {
        val base = baseName(name)
        val kind = kindOf(base)
        return Partition(
            name = name,
            linkPath = linkPath,
            blockPath = blockPath,
            sizeBytes = sizeBytes,
            kind = kind,
            slotSuffix = slotSuffix(name),
            description = descriptionOf(kind),
            risk = riskOf(kind, base),
            protectedReason = protectedReason(base),
            isMounted = isMounted,
            mountPoint = mountPoint,
        )
    }
}
