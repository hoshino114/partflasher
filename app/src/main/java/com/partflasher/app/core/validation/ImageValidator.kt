package com.partflasher.app.core.validation

import com.partflasher.app.core.model.DeviceInfo
import com.partflasher.app.core.model.ImageFormat
import com.partflasher.app.core.model.ImageValidation
import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionKind
import com.partflasher.app.core.model.PartitionRisk
import com.partflasher.app.core.model.StagedImage
import com.partflasher.app.core.model.ValidationIssue
import com.partflasher.app.core.model.ValidationSeverity
import com.partflasher.app.core.partition.PartitionClassifier
import com.partflasher.app.core.util.Format

/**
 * Pure validation of an image/partition pair. Produces user-facing Chinese
 * messages and never touches the filesystem or shell.
 *
 * `canFlash` is false if and only if at least one [ValidationSeverity.ERROR]
 * issue exists. Warnings are informational unless [ImageValidation.requiresExtraConfirm]
 * is true, in which case the UI must ask for an additional explicit confirmation.
 */
class ImageValidator {

    fun validate(
        partition: Partition,
        image: StagedImage,
        activeSlot: String? = null,
        deviceInfo: DeviceInfo? = null,
    ): ImageValidation {
        val issues = mutableListOf<ValidationIssue>()
        val slot = activeSlot?.takeIf { it.isNotBlank() } ?: deviceInfo?.activeSlot
        val format = image.format
        val extraConfirmCodes = mutableSetOf<String>()

        // 1. Protected / forbidden partitions.
        val protectedReason = partition.protectedReason
            ?: PartitionClassifier.protectedReason(partition.baseName)
        if (partition.risk == PartitionRisk.CRITICAL || protectedReason != null) {
            issues.add(
                error(
                    "PROTECTED_PARTITION",
                    "目标分区「${partition.name}」受保护，禁止刷入：" +
                        (protectedReason ?: "该分区属于受保护的敏感分区，写入可能导致设备无法开机或数据丢失。"),
                ),
            )
        }

        // 2. Unsupported containers (v1 only flashes raw images).
        unsupportedContainerMessage(format)?.let { message ->
            issues.add(error("UNSUPPORTED_CONTAINER", message))
        }

        // 3. Size sanity.
        when {
            image.sizeBytes <= 0L -> issues.add(
                error("IMAGE_EMPTY", "镜像文件为空（0 字节），无法刷入，请重新选择镜像。"),
            )

            image.sizeBytes < MIN_IMAGE_BYTES -> issues.add(
                error(
                    "IMAGE_TOO_SMALL",
                    "镜像文件过小（${image.sizeBytes} 字节，小于 1 KiB），不是有效的分区镜像。",
                ),
            )
        }
        if (partition.sizeBytes > 0L && image.sizeBytes > partition.sizeBytes) {
            issues.add(
                error(
                    "IMAGE_TOO_LARGE",
                    "镜像大小（${Format.bytes(image.sizeBytes)}）超过分区容量" +
                        "（${Format.bytes(partition.sizeBytes)}），写入会失败或损坏数据，已阻止刷入。",
                ),
            )
        }
        if (partition.sizeBytes <= 0L) {
            issues.add(
                warning(
                    "PARTITION_SIZE_UNKNOWN",
                    "无法获取目标分区大小，无法校验镜像是否超出分区容量，请务必确认镜像与分区匹配。",
                ),
            )
            extraConfirmCodes.add("PARTITION_SIZE_UNKNOWN")
        }

        // 4. Boot-family format rules.
        val containerError = unsupportedContainerMessage(format) != null
        if (!containerError) {
            when (partition.kind) {
                PartitionKind.BOOT -> if (format != ImageFormat.ANDROID_BOOT) {
                    issues.add(
                        error(
                            "FORMAT_MISMATCH_BOOT",
                            "目标分区「${partition.name}」是 boot 分区，需要 Android boot 镜像（ANDROID!），" +
                                "当前镜像格式为「${image.formatLabel}」。请选择正确的镜像文件。",
                        ),
                    )
                }

                PartitionKind.INIT_BOOT -> if (format != ImageFormat.ANDROID_BOOT) {
                    issues.add(
                        warning(
                            "FORMAT_MISMATCH_INIT_BOOT",
                            "init_boot 分区通常使用 Android boot 格式（v4）镜像，当前格式为" +
                                "「${image.formatLabel}」，请确认镜像是否正确。",
                        ),
                    )
                    extraConfirmCodes.add("FORMAT_MISMATCH_INIT_BOOT")
                }

                PartitionKind.VENDOR_BOOT -> if (format != ImageFormat.VENDOR_BOOT) {
                    issues.add(
                        error(
                            "FORMAT_MISMATCH_VENDOR_BOOT",
                            "目标分区「${partition.name}」是 vendor_boot 分区，需要 vendor_boot 镜像（VNDRBOOT），" +
                                "当前镜像格式为「${image.formatLabel}」。请选择正确的镜像文件。",
                        ),
                    )
                }

                PartitionKind.DTBO -> if (format != ImageFormat.DTBO) {
                    issues.add(
                        error(
                            "FORMAT_MISMATCH_DTBO",
                            "目标分区「${partition.name}」是 dtbo 分区，需要 dtbo 镜像（magic 0xd7b7ab1e），" +
                                "当前镜像格式为「${image.formatLabel}」。请选择正确的镜像文件。",
                        ),
                    )
                }

                PartitionKind.VBMETA -> if (format != ImageFormat.VBMETA) {
                    issues.add(
                        error(
                            "FORMAT_MISMATCH_VBMETA",
                            "目标分区「${partition.name}」是 vbmeta 分区，需要 vbmeta 镜像（AVB0），" +
                                "当前镜像格式为「${image.formatLabel}」。请选择正确的镜像文件。",
                        ),
                    )
                }

                PartitionKind.RECOVERY -> when (format) {
                    ImageFormat.ANDROID_BOOT -> issues.add(
                        warning(
                            "RECOVERY_BOOT_IMAGE",
                            "目标分区「${partition.name}」是 recovery 分区：当前镜像是 Android boot 格式。" +
                                "recovery 分区通常使用 boot/recovery 格式，请确认该镜像支持 recovery 启动。",
                        ),
                    )

                    ImageFormat.UNKNOWN -> {
                        issues.add(
                            warning(
                                "FORMAT_MISMATCH_RECOVERY",
                                "无法识别镜像格式；recovery 分区通常使用 Android boot/recovery 镜像，" +
                                    "请确认镜像是否正确。",
                            ),
                        )
                        extraConfirmCodes.add("FORMAT_MISMATCH_RECOVERY")
                    }

                    else -> issues.add(
                        error(
                            "FORMAT_MISMATCH_RECOVERY",
                            "目标分区「${partition.name}」是 recovery 分区，需要 Android boot/recovery 镜像" +
                                "（ANDROID!），当前镜像格式为「${image.formatLabel}」，已阻止刷入。" +
                                "请选择正确的镜像文件。",
                        ),
                    )
                }

                PartitionKind.SUPER -> {
                    issues.add(
                        warning(
                            "SUPER_PARTITION",
                            "super 是动态分区容器，直接写入原始镜像可能导致动态分区布局损坏，风险较高。" +
                                "建议使用官方 fastboot 刷写 super，或在确认镜像为完整 raw super 镜像后再继续。",
                        ),
                    )
                    extraConfirmCodes.add("SUPER_PARTITION")
                }

                PartitionKind.SYSTEM,
                PartitionKind.VENDOR,
                PartitionKind.PRODUCT,
                PartitionKind.ODM -> validateFilesystemTarget(partition, image, issues, extraConfirmCodes)

                PartitionKind.FIRMWARE,
                PartitionKind.MODEM -> {
                    issues.add(
                        warning(
                            "FIRMWARE_TARGET",
                            "目标分区「${partition.name}」属于底层固件/基带相关分区，" +
                                "写入错误可能导致设备无法开机或失去通信能力，请务必确认镜像来源与版本完全匹配。",
                        ),
                    )
                    extraConfirmCodes.add("FIRMWARE_TARGET")
                }

                PartitionKind.OTHER -> issues.add(
                    info(
                        "UNKNOWN_PARTITION_KIND",
                        "目标分区「${partition.name}」的类型无法识别，请确认它确实是需要写入的分区。",
                    ),
                )

                else -> Unit
            }
        }

        // 5. General unknown-format warning (unless a more specific issue exists).
        if (format == ImageFormat.UNKNOWN && !containerError &&
            issues.none {
                it.severity == ValidationSeverity.ERROR &&
                    (it.code.startsWith("FORMAT_MISMATCH") || it.code == "PROTECTED_PARTITION")
            } && issues.none { it.code == "UNKNOWN_FORMAT_FILESYSTEM" }
        ) {
            issues.add(
                warning(
                    "UNKNOWN_FORMAT",
                    "无法识别镜像格式，无法确认镜像与目标分区匹配。刷入未知格式镜像可能导致设备无法启动，请确认文件正确。",
                ),
            )
            extraConfirmCodes.add("UNKNOWN_FORMAT")
        }

        // 6. Slot warning.
        val slotSuffix = partition.slotSuffix
        if (slotSuffix != null) {
            when {
                slot == null -> issues.add(
                    info(
                        "ACTIVE_SLOT_UNKNOWN",
                        "无法确定当前活动槽位，目标分区位于 ${slotSuffix.removePrefix("_").uppercase()} 槽，" +
                            "请确认这是你想要刷入的槽位。",
                    ),
                )

                slot != slotSuffix -> issues.add(
                    warning(
                        "NON_ACTIVE_SLOT",
                        "目标分区「${partition.name}」位于 ${slotSuffix.removePrefix("_").uppercase()} 槽，" +
                            "当前活动槽位为 ${slot.removePrefix("_").uppercase()}：写入非活动槽位不会立即生效，" +
                            "重启后可能仍从当前槽位启动。",
                    ),
                )
            }
        }

        // 7. Filename/type mismatch heuristic.
        filenameMismatchMessage(partition, image.displayName)?.let { message ->
            issues.add(warning("FILENAME_MISMATCH", message))
        }

        val errors = issues.count { it.severity == ValidationSeverity.ERROR }
        val warnings = issues.count { it.severity == ValidationSeverity.WARNING }
        val summary = when {
            errors > 0 -> "校验未通过：发现 $errors 个错误，已禁止刷入。"
            warnings > 0 -> "校验通过，但有 $warnings 项注意事项，请确认后继续。"
            else -> "校验通过，可以刷入。"
        }
        return ImageValidation(
            issues = issues,
            canFlash = errors == 0,
            requiresExtraConfirm = errors == 0 && extraConfirmCodes.isNotEmpty(),
            summary = summary,
        )
    }

    private fun validateFilesystemTarget(
        partition: Partition,
        image: StagedImage,
        issues: MutableList<ValidationIssue>,
        extraConfirmCodes: MutableSet<String>,
    ) {
        when (image.format) {
            ImageFormat.EXT4, ImageFormat.EROFS -> issues.add(
                info("FORMAT_MATCH_FILESYSTEM", "镜像格式「${image.formatLabel}」与文件系统目标分区匹配。"),
            )

            ImageFormat.UNKNOWN -> {
                issues.add(
                    warning(
                        "UNKNOWN_FORMAT_FILESYSTEM",
                        "目标分区「${partition.name}」是文件系统分区，但无法识别镜像格式（应为 ext4/EROFS）。" +
                            "写入未知格式可能导致系统无法启动，请确认后继续。",
                    ),
                )
                extraConfirmCodes.add("UNKNOWN_FORMAT_FILESYSTEM")
            }

            else -> {
                issues.add(
                    warning(
                        "FORMAT_MISMATCH_FILESYSTEM",
                        "目标分区「${partition.name}」是文件系统分区，通常需要 ext4/EROFS 镜像，当前格式为" +
                            "「${image.formatLabel}」，请确认没有选错镜像。",
                    ),
                )
                extraConfirmCodes.add("FORMAT_MISMATCH_FILESYSTEM")
            }
        }
    }

    private fun unsupportedContainerMessage(format: ImageFormat): String? = when (format) {
        ImageFormat.SPARSE ->
            "检测到 Android sparse 镜像：当前版本仅支持原始（raw）镜像。" +
                "请先使用 simg2img 转换为 raw 镜像（例如：simg2img image.img raw.img）后再刷入。"

        ImageFormat.GZIP ->
            "检测到 gzip 压缩镜像：请先解压为原始 raw 镜像（例如：gunzip image.img.gz）后再刷入。"

        ImageFormat.LZ4 ->
            "检测到 LZ4 压缩镜像：请先解压为原始 raw 镜像（例如：lz4 -d image.img.lz4 image.img）后再刷入。"

        ImageFormat.ZSTD ->
            "检测到 Zstandard 压缩镜像：请先解压为原始 raw 镜像（例如：zstd -d image.img.zst）后再刷入。"

        ImageFormat.PAYLOAD ->
            "检测到 Android OTA payload 镜像：不能直接刷入分区。" +
                "请从完整 OTA 包中提取对应分区镜像（例如使用 payload_dumper）后再刷入。"

        else -> null
    }

    private fun filenameMismatchMessage(partition: Partition, displayName: String): String? {
        val targetToken = targetToken(partition.kind) ?: return null
        val fileName = displayName.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (fileName.isBlank()) return null
        val foundToken = FILE_TOKENS.firstOrNull { fileName.contains(it) } ?: return null
        if (foundToken == targetToken) return null
        return "所选文件名中包含「$foundToken」，但目标分区「${partition.name}」是 ${targetToken} 类型，" +
            "请确认没有选错镜像文件。"
    }

    private fun targetToken(kind: PartitionKind): String? = when (kind) {
        PartitionKind.BOOT -> "boot"
        PartitionKind.INIT_BOOT -> "init_boot"
        PartitionKind.VENDOR_BOOT -> "vendor_boot"
        PartitionKind.RECOVERY -> "recovery"
        PartitionKind.DTBO -> "dtbo"
        PartitionKind.VBMETA -> "vbmeta"
        PartitionKind.SUPER -> "super"
        PartitionKind.SYSTEM -> "system"
        PartitionKind.VENDOR -> "vendor"
        PartitionKind.PRODUCT -> "product"
        PartitionKind.ODM -> "odm"
        else -> null
    }

    private fun error(code: String, message: String) =
        ValidationIssue(ValidationSeverity.ERROR, code, message)

    private fun warning(code: String, message: String) =
        ValidationIssue(ValidationSeverity.WARNING, code, message)

    private fun info(code: String, message: String) =
        ValidationIssue(ValidationSeverity.INFO, code, message)

    private companion object {
        const val MIN_IMAGE_BYTES = 1024L

        /** Longest tokens first so `vendor_boot` wins over `boot`. */
        val FILE_TOKENS = listOf(
            "vendor_boot", "init_boot", "recovery", "vbmeta", "dtbo",
            "super", "system", "vendor", "product", "odm", "boot",
        )
    }
}
