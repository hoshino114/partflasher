package com.partflasher.app.ui.util

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.partflasher.app.R
import com.partflasher.app.core.model.FlashState
import com.partflasher.app.core.model.LogLevel
import com.partflasher.app.core.model.PartitionKind
import com.partflasher.app.core.model.PartitionRisk
import com.partflasher.app.core.model.RootSolution
import com.partflasher.app.ui.theme.statusColors

/** Localized label for a [PartitionKind]. */
@StringRes
fun partitionKindLabelRes(kind: PartitionKind): Int = when (kind) {
    PartitionKind.BOOT -> R.string.partition_kind_boot
    PartitionKind.RECOVERY -> R.string.partition_kind_recovery
    PartitionKind.INIT_BOOT -> R.string.partition_kind_init_boot
    PartitionKind.VENDOR_BOOT -> R.string.partition_kind_vendor_boot
    PartitionKind.DTBO -> R.string.partition_kind_dtbo
    PartitionKind.VBMETA -> R.string.partition_kind_vbmeta
    PartitionKind.SUPER -> R.string.partition_kind_super
    PartitionKind.SYSTEM -> R.string.partition_kind_system
    PartitionKind.VENDOR -> R.string.partition_kind_vendor
    PartitionKind.PRODUCT -> R.string.partition_kind_product
    PartitionKind.ODM -> R.string.partition_kind_odm
    PartitionKind.DATA -> R.string.partition_kind_data
    PartitionKind.METADATA -> R.string.partition_kind_metadata
    PartitionKind.FIRMWARE -> R.string.partition_kind_firmware
    PartitionKind.MODEM -> R.string.partition_kind_modem
    PartitionKind.MISC -> R.string.partition_kind_misc
    PartitionKind.PERSIST -> R.string.partition_kind_persist
    PartitionKind.OTHER -> R.string.partition_kind_other
}

@Composable
fun partitionKindLabel(kind: PartitionKind): String = stringResource(partitionKindLabelRes(kind))

/** Decorative icon for a partition kind (quick cards). */
fun partitionKindIcon(kind: PartitionKind): ImageVector = when (kind) {
    PartitionKind.BOOT -> Icons.Filled.Memory
    PartitionKind.RECOVERY -> Icons.Filled.RestartAlt
    PartitionKind.INIT_BOOT -> Icons.Filled.PowerSettingsNew
    PartitionKind.VENDOR_BOOT -> Icons.Filled.DeveloperBoard
    PartitionKind.DTBO -> Icons.Filled.DeviceHub
    PartitionKind.VBMETA -> Icons.Filled.Shield
    PartitionKind.SUPER -> Icons.Filled.Storage
    PartitionKind.SYSTEM -> Icons.Filled.Storage
    PartitionKind.VENDOR -> Icons.Filled.DeveloperBoard
    PartitionKind.PRODUCT -> Icons.Filled.Storage
    PartitionKind.ODM -> Icons.Filled.DeveloperBoard
    PartitionKind.DATA -> Icons.Filled.Storage
    PartitionKind.METADATA -> Icons.Filled.Storage
    PartitionKind.FIRMWARE -> Icons.Filled.SystemUpdate
    PartitionKind.MODEM -> Icons.Filled.Usb
    PartitionKind.MISC -> Icons.Filled.Security
    PartitionKind.PERSIST -> Icons.Filled.Security
    PartitionKind.OTHER -> Icons.Filled.Storage
}

/** Localized label for the detected root solution. */
@Composable
fun rootSolutionLabel(solution: RootSolution): String = stringResource(
    when (solution) {
        RootSolution.MAGISK -> R.string.solution_magisk
        RootSolution.KERNELSU -> R.string.solution_kernelsu
        RootSolution.APATCH -> R.string.solution_apatch
        RootSolution.OTHER -> R.string.solution_other
        RootSolution.NONE -> R.string.solution_none
        RootSolution.UNKNOWN -> R.string.solution_unknown
    },
)

/** "A" / "B" for a slot suffix, null for non-A/B devices. */
fun slotLetter(slotSuffix: String?): String? = when (slotSuffix) {
    "_a" -> "A"
    "_b" -> "B"
    else -> null
}

/** Localized slot label for the active slot, e.g. "A 槽位" or "无（非 A/B）". */
@Composable
fun activeSlotLabel(activeSlot: String?): String = stringResource(
    when (activeSlot) {
        "_a" -> R.string.slot_label_a
        "_b" -> R.string.slot_label_b
        else -> R.string.slot_label_none
    },
)

@Composable
fun activeSlotShortLabel(activeSlot: String?): String = when (activeSlot) {
    "_a" -> "A"
    "_b" -> "B"
    else -> stringResource(R.string.partition_slot_none)
}

/** Severity tint for validation issues and log levels. */
@Composable
fun severityTint(severity: com.partflasher.app.core.model.ValidationSeverity): Color =
    when (severity) {
        com.partflasher.app.core.model.ValidationSeverity.ERROR -> MaterialTheme.colorScheme.error
        com.partflasher.app.core.model.ValidationSeverity.WARNING -> MaterialTheme.statusColors.warning
        com.partflasher.app.core.model.ValidationSeverity.INFO -> MaterialTheme.colorScheme.primary
    }

@Composable
fun severityContainerColor(severity: com.partflasher.app.core.model.ValidationSeverity): Color =
    when (severity) {
        com.partflasher.app.core.model.ValidationSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
        com.partflasher.app.core.model.ValidationSeverity.WARNING -> MaterialTheme.statusColors.warningContainer
        com.partflasher.app.core.model.ValidationSeverity.INFO -> MaterialTheme.colorScheme.primaryContainer
    }

@Composable
fun logLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.primary
    LogLevel.SUCCESS -> MaterialTheme.statusColors.success
    LogLevel.WARN -> MaterialTheme.statusColors.warning
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.COMMAND -> MaterialTheme.colorScheme.secondary
}

@Composable
fun logLevelLabel(level: LogLevel): String = when (level) {
    LogLevel.DEBUG -> stringResource(R.string.logs_level_debug)
    LogLevel.INFO -> stringResource(R.string.logs_level_info)
    LogLevel.SUCCESS -> stringResource(R.string.logs_level_success)
    LogLevel.WARN -> stringResource(R.string.logs_level_warn)
    LogLevel.ERROR -> stringResource(R.string.logs_level_error)
    LogLevel.COMMAND -> stringResource(R.string.logs_level_command)
}

@Composable
fun flashStageLabel(flashState: FlashState): String = stringResource(
    when (flashState) {
        is FlashState.Preparing -> R.string.flash_stage_preparing
        is FlashState.BackingUp -> R.string.flash_stage_backing_up
        is FlashState.Flashing -> R.string.flash_stage_flashing
        is FlashState.Verifying -> R.string.flash_stage_verifying
        else -> R.string.flash_stage_idle
    },
)

@StringRes
fun partitionRiskLabelRes(risk: PartitionRisk): Int = when (risk) {
    PartitionRisk.CRITICAL -> R.string.partition_risk_critical
    PartitionRisk.CAUTION -> R.string.partition_risk_caution
    PartitionRisk.NORMAL -> R.string.partition_kind_other
}
