package com.partflasher.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.partflasher.app.R
import com.partflasher.app.core.model.FlashStage
import com.partflasher.app.core.model.FlashState
import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.StagedImage
import com.partflasher.app.ui.theme.statusColors
import com.partflasher.app.ui.util.Formats
import com.partflasher.app.ui.util.activeSlotLabel
import com.partflasher.app.ui.util.flashStageLabel
import com.partflasher.app.ui.util.logLevelColor
import com.partflasher.app.viewmodel.MainUiState
import com.partflasher.app.ui.theme.monoSmall

private const val MAX_BACKUP_BYTES = 512L * 1024L * 1024L

/**
 * Second confirmation before any destructive write. Requires an explicit risk
 * acknowledgement checkbox; the destructive action stays disabled until it is
 * ticked. Backup defaults to on for boot-family partitions and verification
 * defaults to on for every flash.
 */
@Composable
fun ConfirmFlashDialog(
    partition: Partition,
    image: StagedImage,
    activeSlot: String?,
    requiresExtraConfirm: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (createBackup: Boolean, verifyAfterWrite: Boolean) -> Unit,
) {
    val backupAvailable = partition.sizeBytes in 1L..MAX_BACKUP_BYTES
    var createBackup by rememberSaveable(partition.name) {
        mutableStateOf(partition.isBootFamily && backupAvailable)
    }
    var verifyAfterWrite by rememberSaveable(partition.name) { mutableStateOf(true) }
    var acknowledged by rememberSaveable(partition.name) { mutableStateOf(false) }
    var extraAcknowledged by rememberSaveable(partition.name) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = stringResource(R.string.confirm_icon_desc),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
        },
        title = { Text(stringResource(R.string.confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MessageBlock(
                    text = stringResource(R.string.confirm_warning),
                    icon = Icons.Filled.WarningAmber,
                    iconContentDescription = stringResource(R.string.status_icon_warning),
                )
                if (requiresExtraConfirm) {
                    MessageBlock(
                        text = stringResource(R.string.confirm_extra_warning),
                        title = stringResource(R.string.common_warning),
                        icon = Icons.Filled.Lock,
                        iconContentDescription = stringResource(R.string.status_icon_warning),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .toggleable(
                                value = extraAcknowledged,
                                role = Role.Checkbox,
                                onValueChange = { extraAcknowledged = it },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = extraAcknowledged, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.confirm_extra_ack),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.confirm_section_target),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                LabelValueRow(
                    label = stringResource(R.string.confirm_partition_name),
                    value = partition.name,
                    mono = true,
                    labelWidth = 88.dp,
                )
                LabelValueRow(
                    label = stringResource(R.string.confirm_partition_path),
                    value = partition.blockPath,
                    mono = true,
                    selectable = true,
                    labelWidth = 88.dp,
                )
                LabelValueRow(
                    label = stringResource(R.string.confirm_partition_size),
                    value = Formats.bytes(partition.sizeBytes),
                    labelWidth = 88.dp,
                )
                LabelValueRow(
                    label = stringResource(R.string.confirm_partition_slot),
                    value = activeSlotLabel(activeSlot),
                    labelWidth = 88.dp,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = stringResource(R.string.confirm_section_image),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                LabelValueRow(
                    label = stringResource(R.string.confirm_image_name),
                    value = image.displayName,
                    labelWidth = 88.dp,
                    maxLines = 2,
                )
                LabelValueRow(
                    label = stringResource(R.string.confirm_image_size),
                    value = Formats.bytes(image.sizeBytes),
                    labelWidth = 88.dp,
                )
                LabelValueRow(
                    label = stringResource(R.string.confirm_image_format),
                    value = image.formatLabel,
                    labelWidth = 88.dp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.confirm_image_sha),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MonoText(
                            text = image.sha256,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.monoSmall,
                            selectable = true,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                SwitchRow(
                    title = stringResource(R.string.confirm_backup_title),
                    description = if (backupAvailable) {
                        stringResource(R.string.confirm_backup_desc) + " · " +
                            stringResource(
                                R.string.confirm_backup_size,
                                Formats.bytes(partition.sizeBytes),
                            )
                    } else {
                        stringResource(R.string.confirm_backup_too_large)
                    },
                    checked = createBackup,
                    enabled = backupAvailable,
                    onCheckedChange = { createBackup = it },
                )
                SwitchRow(
                    title = stringResource(R.string.confirm_verify_title),
                    description = stringResource(R.string.confirm_verify_desc),
                    checked = verifyAfterWrite,
                    enabled = true,
                    onCheckedChange = { verifyAfterWrite = it },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .toggleable(
                            value = acknowledged,
                            role = Role.Checkbox,
                            onValueChange = { acknowledged = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.confirm_ack),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(createBackup, verifyAfterWrite) },
                enabled = acknowledged && (!requiresExtraConfirm || extraAcknowledged),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Non-dismissible progress overlay. There is deliberately no cancel action:
 * once the write phase starts, interrupting it is unsafe.
 */
@Composable
fun FlashProgressDialog(state: MainUiState) {
    val flash = state.flashState
    val progress: Float? = when (flash) {
        is FlashState.Flashing -> flash.progress
        is FlashState.BackingUp -> flash.progress
        else -> null
    }
    val logTail = state.logs.takeLast(5)

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.FlashOn,
                                    contentDescription = stringResource(R.string.flash_progress_icon_desc),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.flash_progress_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val targetName = state.flashTargetName ?: state.selectedPartition?.name
                            if (targetName != null) {
                                Text(
                                    text = stringResource(R.string.flash_progress_target, targetName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    }

                    Text(
                        text = flashStageLabel(flash),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                        )
                    }

                    when (flash) {
                        is FlashState.Flashing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MonoText(
                                    text = stringResource(
                                        R.string.flash_progress_bytes,
                                        Formats.bytes(flash.bytesWritten),
                                        Formats.bytes(flash.totalBytes),
                                    ),
                                    style = MaterialTheme.typography.monoSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = stringResource(
                                        R.string.flash_progress_percent,
                                        Formats.percent(flash.progress),
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(
                                        R.string.flash_progress_speed,
                                        Formats.speed(flash.bytesPerSecond),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = stringResource(
                                        R.string.flash_progress_elapsed,
                                        Formats.duration(flash.elapsedMillis),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        is FlashState.BackingUp -> {
                            Text(
                                text = stringResource(R.string.flash_progress_backup_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> Unit
                    }

                    MessageBlock(
                        text = stringResource(R.string.flash_progress_warning),
                        icon = Icons.Filled.WarningAmber,
                        iconContentDescription = stringResource(R.string.status_icon_warning),
                    )

                    if (logTail.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.flash_progress_log_tail),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    logTail.forEach { entry ->
                                        MonoText(
                                            text = entry.message,
                                            style = MaterialTheme.typography.monoSmall,
                                            color = logLevelColor(entry.level),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.flash_progress_no_cancel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Terminal flash outcome with partition, duration, verification and backup path. */
@Composable
fun FlashResultDialog(
    state: MainUiState,
    onDismiss: () -> Unit,
    onViewLogs: () -> Unit,
) {
    when (val flash = state.flashState) {
        is FlashState.Success -> SuccessResultDialog(state, flash, onDismiss, onViewLogs)
        is FlashState.Failed -> FailureResultDialog(state, flash, onDismiss, onViewLogs)
        else -> Unit
    }
}

@Composable
private fun SuccessResultDialog(
    state: MainUiState,
    flash: FlashState.Success,
    onDismiss: () -> Unit,
    onViewLogs: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.result_success_icon_desc),
                tint = MaterialTheme.statusColors.success,
                modifier = Modifier.size(30.dp),
            )
        },
        title = { Text(stringResource(R.string.result_success_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val targetName = state.flashTargetName ?: state.selectedPartition?.name
                if (targetName != null) {
                    LabelValueRow(
                        label = stringResource(R.string.result_partition),
                        value = targetName,
                        mono = true,
                    )
                }
                LabelValueRow(
                    label = stringResource(R.string.result_duration),
                    value = Formats.duration(flash.elapsedMillis),
                )
                LabelValueRow(
                    label = stringResource(R.string.result_verified),
                    value = when (flash.verified) {
                        true -> stringResource(R.string.result_verified_ok)
                        false -> stringResource(R.string.result_verified_mismatch)
                        null -> stringResource(R.string.result_verified_skipped)
                    },
                    valueColor = when (flash.verified) {
                        false -> MaterialTheme.colorScheme.error
                        true -> MaterialTheme.statusColors.success
                        null -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (flash.outputPath.isNotBlank()) {
                    LabelValueRow(
                        label = stringResource(R.string.result_output_path),
                        value = flash.outputPath,
                        mono = true,
                        selectable = true,
                    )
                }
                if (!flash.backupPath.isNullOrBlank()) {
                    LabelValueRow(
                        label = stringResource(R.string.result_backup),
                        value = flash.backupPath,
                        mono = true,
                        selectable = true,
                    )
                } else {
                    LabelValueRow(
                        label = stringResource(R.string.result_backup),
                        value = stringResource(R.string.result_no_backup),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MessageBlock(
                    text = stringResource(R.string.result_success_note),
                    icon = Icons.Filled.CheckCircle,
                    containerColor = MaterialTheme.statusColors.successContainer,
                    contentColor = MaterialTheme.statusColors.onSuccessContainer,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.result_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onViewLogs) {
                Text(stringResource(R.string.result_view_logs))
            }
        },
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun FailureResultDialog(
    state: MainUiState,
    flash: FlashState.Failed,
    onDismiss: () -> Unit,
    onViewLogs: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = stringResource(R.string.result_failed_icon_desc),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(30.dp),
            )
        },
        title = { Text(stringResource(R.string.result_failed_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val targetName = state.flashTargetName ?: state.selectedPartition?.name
                if (targetName != null) {
                    LabelValueRow(
                        label = stringResource(R.string.result_partition),
                        value = targetName,
                        mono = true,
                    )
                }
                LabelValueRow(
                    label = stringResource(R.string.result_failure_stage),
                    value = flashStageText(flash.stage),
                    valueColor = MaterialTheme.colorScheme.error,
                )
                LabelValueRow(
                    label = stringResource(R.string.result_failure_reason),
                    value = flash.message,
                )
                if (!flash.detail.isNullOrBlank()) {
                    LabelValueRow(
                        label = stringResource(R.string.result_failure_detail),
                        value = flash.detail,
                        mono = true,
                        selectable = true,
                    )
                }
                if (!flash.backupPath.isNullOrBlank()) {
                    LabelValueRow(
                        label = stringResource(R.string.result_backup),
                        value = flash.backupPath,
                        mono = true,
                        selectable = true,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MessageBlock(
                    text = stringResource(R.string.result_failure_note),
                    icon = Icons.Filled.Lock,
                    iconContentDescription = stringResource(R.string.status_icon_warning),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onViewLogs,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.result_view_logs))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.result_done))
            }
        },
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun flashStageText(stage: FlashStage): String = stringResource(
    when (stage) {
        FlashStage.PREPARING -> R.string.flash_stage_preparing
        FlashStage.BACKING_UP -> R.string.flash_stage_backing_up
        FlashStage.FLASHING -> R.string.flash_stage_flashing
        FlashStage.VERIFYING -> R.string.flash_stage_verifying
        else -> R.string.flash_stage_idle
    },
)
