package com.partflasher.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partflasher.app.R
import com.partflasher.app.core.model.ImageValidation
import com.partflasher.app.core.model.ValidationIssue
import com.partflasher.app.core.model.ValidationSeverity
import com.partflasher.app.ui.theme.statusColors
import com.partflasher.app.ui.util.Formats
import com.partflasher.app.ui.util.activeSlotLabel
import com.partflasher.app.ui.util.partitionKindIcon
import com.partflasher.app.ui.util.severityContainerColor
import com.partflasher.app.ui.util.severityTint
import com.partflasher.app.ui.util.slotLetter
import com.partflasher.app.viewmodel.FlashBlockReason
import com.partflasher.app.viewmodel.MainUiState
import com.partflasher.app.ui.theme.monoSmall

/**
 * The single place where the user reviews target + image + validation and
 * launches the confirmation dialog. Every disabled state explains itself.
 */
@Composable
fun SelectionCard(
    state: MainUiState,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onClearSelection: () -> Unit,
    onFlashClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val partition = state.selectedPartition
    val image = state.stagedImage

    SectionCard(
        title = stringResource(R.string.selection_title),
        modifier = modifier,
        icon = Icons.Filled.FlashOn,
        iconContentDescription = null,
        headerTrailing = if (partition != null && !state.staging) {
            {
                TextButton(onClick = onClearSelection) {
                    Text(
                        text = stringResource(R.string.selection_clear_selection),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        } else {
            null
        },
    ) {
        // Target partition -------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(
                icon = if (partition != null) partitionKindIcon(partition.kind) else Icons.Filled.Memory,
                containerColor = if (partition != null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (partition != null) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                size = 34.dp,
                contentDescription = stringResource(R.string.selection_partition_icon_desc),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = partition?.name ?: stringResource(R.string.selection_target_empty),
                    style = if (partition != null) {
                        MaterialTheme.typography.titleSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (partition != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (partition != null) {
                    val targetSlotLetter = slotLetter(partition.slotSuffix)
                    val targetSlotText = if (targetSlotLetter != null) {
                        stringResource(R.string.slot_badge, targetSlotLetter)
                    } else {
                        null
                    }
                    val targetSizeText = stringResource(
                        R.string.partition_size_value,
                        Formats.bytes(partition.sizeBytes),
                    )
                    val targetReason = partition.protectedReason?.takeIf { it.isNotBlank() }
                    Text(
                        text = listOfNotNull(targetSlotText, targetSizeText, targetReason)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.selection_target_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (partition != null) {
            LabelValueRow(
                label = stringResource(R.string.selection_partition_path),
                value = partition.blockPath,
                mono = true,
                selectable = true,
            )
            LabelValueRow(
                label = stringResource(R.string.selection_partition_slot),
                value = activeSlotLabel(state.activeSlot),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Image ------------------------------------------------------------
        when {
            state.staging -> {
                val stagingProgressKnown = state.stagingProgress >= 0f
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.selection_staging),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (stagingProgressKnown) {
                            Text(
                                text = stringResource(
                                    R.string.flash_progress_percent,
                                    Formats.percent(state.stagingProgress),
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (stagingProgressKnown) {
                        LinearProgressIndicator(
                            progress = { state.stagingProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.selection_staging_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.stagingError != null -> {
                MessageBlock(
                    text = state.stagingError,
                    title = stringResource(R.string.selection_staging_failed),
                    icon = Icons.Filled.ErrorOutline,
                    iconContentDescription = stringResource(R.string.status_icon_error),
                )
                OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.selection_choose_image))
                }
            }

            image != null -> {
                Text(
                    text = image.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LabelValueRow(
                    label = stringResource(R.string.selection_image_size),
                    value = Formats.bytes(image.sizeBytes),
                )
                LabelValueRow(
                    label = stringResource(R.string.selection_image_format),
                    value = image.formatLabel,
                )
                if (image.headerDetails.isNotBlank()) {
                    LabelValueRow(
                        label = stringResource(R.string.selection_image_header),
                        value = image.headerDetails,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.selection_image_sha),
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
                            color = MaterialTheme.colorScheme.onSurface,
                            selectable = true,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPickImage,
                        enabled = !state.isFlashing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.selection_change_image))
                    }
                    TextButton(
                        onClick = onClearImage,
                        enabled = !state.isFlashing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.selection_clear_image))
                    }
                }
            }

            else -> {
                Text(
                    text = stringResource(R.string.selection_image_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.selection_image_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onPickImage,
                    enabled = !state.isFlashing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.selection_choose_image))
                }
            }
        }

        // Validation -------------------------------------------------------
        if (partition != null && image != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ValidationBlock(
                validating = state.validating,
                validation = state.validation,
            )
        }

        // Primary action ---------------------------------------------------
        Button(
            onClick = onFlashClick,
            enabled = state.canFlash,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.FlashOn,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.flash_cta),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (!state.canFlash) {
            Text(
                text = flashBlockReasonText(state.flashBlockReason),
                style = MaterialTheme.typography.bodySmall,
                color = when (state.flashBlockReason) {
                    FlashBlockReason.VALIDATION_ERRORS -> MaterialTheme.colorScheme.error
                    FlashBlockReason.ROOT_REQUIRED -> MaterialTheme.statusColors.warning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else {
            Text(
                text = stringResource(R.string.selection_image_ready),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.statusColors.success,
            )
        }
    }
}

@Composable
private fun flashBlockReasonText(reason: FlashBlockReason): String = stringResource(
    when (reason) {
        FlashBlockReason.ROOT_REQUIRED -> R.string.flash_cta_hint_root
        FlashBlockReason.NO_TARGET -> R.string.flash_cta_hint_no_target
        FlashBlockReason.NO_IMAGE -> R.string.flash_cta_hint_no_image
        FlashBlockReason.IMAGE_STAGING -> R.string.flash_cta_hint_staging
        FlashBlockReason.VALIDATING -> R.string.flash_cta_hint_validation
        FlashBlockReason.VALIDATION_ERRORS -> R.string.flash_cta_hint_errors
        FlashBlockReason.FLASHING -> R.string.flash_cta_hint_flashing
        FlashBlockReason.NONE -> R.string.selection_image_ready
    },
)

@Composable
private fun ValidationBlock(
    validating: Boolean,
    validation: ImageValidation?,
) {
    var showAll by rememberSaveable(validation?.issues?.size) { mutableStateOf(false) }

    if (validating) {
        InlineLoading(text = stringResource(R.string.validation_checking))
        return
    }
    if (validation == null) {
        Text(
            text = stringResource(R.string.validation_checking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (validation.canFlash) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = stringResource(
                if (validation.canFlash) R.string.status_icon_success else R.string.status_icon_error,
            ),
            tint = if (validation.canFlash) {
                MaterialTheme.statusColors.success
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(
                if (validation.canFlash) R.string.validation_passed else R.string.validation_blocked,
            ),
            style = MaterialTheme.typography.titleSmall,
            color = if (validation.canFlash) {
                MaterialTheme.statusColors.success
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.weight(1f),
        )
    }

    if (validation.summary.isNotBlank()) {
        Text(
            text = validation.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Errors first, then warnings, then informational notes, each with a
    // counted group header so the user can triage at a glance.
    val orderedIssues = validation.issues.sortedByDescending { it.severity.ordinal }
    val visibleIssues: List<ValidationIssue> = if (showAll) {
        orderedIssues
    } else {
        orderedIssues.take(4)
    }
    listOf(
        ValidationSeverity.ERROR,
        ValidationSeverity.WARNING,
        ValidationSeverity.INFO,
    ).forEach { severity ->
        val groupTotal = orderedIssues.count { it.severity == severity }
        val groupVisible = visibleIssues.filter { it.severity == severity }
        if (groupTotal > 0 && groupVisible.isNotEmpty()) {
            Text(
                text = stringResource(
                    when (severity) {
                        ValidationSeverity.ERROR -> R.string.validation_errors
                        ValidationSeverity.WARNING -> R.string.validation_warnings
                        ValidationSeverity.INFO -> R.string.validation_infos
                    },
                    groupTotal,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = severityTint(severity),
                modifier = Modifier.padding(top = 4.dp),
            )
            groupVisible.forEach { issue ->
                ValidationIssueRow(issue)
            }
        }
    }
    if (orderedIssues.size > 4) {
        TextButton(onClick = { showAll = !showAll }) {
            Text(
                text = if (showAll) {
                    stringResource(R.string.validation_collapse)
                } else {
                    stringResource(R.string.validation_more_issues, orderedIssues.size - 4)
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ValidationIssueRow(issue: ValidationIssue) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = severityContainerColor(issue.severity),
            contentColor = severityTint(issue.severity),
            modifier = Modifier.size(18.dp),
        ) {
            Icon(
                imageVector = when (issue.severity) {
                    ValidationSeverity.ERROR -> Icons.Filled.ErrorOutline
                    ValidationSeverity.WARNING -> Icons.Filled.WarningAmber
                    ValidationSeverity.INFO -> Icons.Filled.Info
                },
                contentDescription = stringResource(
                    when (issue.severity) {
                        ValidationSeverity.ERROR -> R.string.status_icon_error
                        ValidationSeverity.WARNING -> R.string.status_icon_warning
                        ValidationSeverity.INFO -> R.string.status_icon_info
                    },
                ),
                modifier = Modifier
                    .padding(3.dp)
                    .size(12.dp),
            )
        }
        Text(
            text = issue.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
