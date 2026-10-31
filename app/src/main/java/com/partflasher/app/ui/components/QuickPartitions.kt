package com.partflasher.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partflasher.app.R
import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionKind
import com.partflasher.app.ui.util.Formats
import com.partflasher.app.ui.util.partitionKindIcon
import com.partflasher.app.ui.theme.monoMedium
import com.partflasher.app.ui.theme.monoSmall

private data class QuickTarget(
    val kind: PartitionKind,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
)

private val quickTargets = listOf(
    QuickTarget(PartitionKind.BOOT, R.string.quick_boot_title, R.string.quick_boot_desc),
    QuickTarget(PartitionKind.RECOVERY, R.string.quick_recovery_title, R.string.quick_recovery_desc),
    QuickTarget(PartitionKind.INIT_BOOT, R.string.quick_init_boot_title, R.string.quick_init_boot_desc),
    QuickTarget(PartitionKind.VENDOR_BOOT, R.string.quick_vendor_boot_title, R.string.quick_vendor_boot_desc),
    QuickTarget(PartitionKind.DTBO, R.string.quick_dtbo_title, R.string.quick_dtbo_desc),
    QuickTarget(PartitionKind.VBMETA, R.string.quick_vbmeta_title, R.string.quick_vbmeta_desc),
)

/**
 * Resolves the best candidate for a quick action: prefer the active slot, then
 * a slotless partition, then the first entry (e.g. slot A on non-A/B layouts).
 */
private fun findCandidate(
    partitions: List<Partition>,
    kind: PartitionKind,
    activeSlot: String?,
): Partition? {
    val candidates = partitions.filter { it.kind == kind }
    if (candidates.isEmpty()) return null
    if (!activeSlot.isNullOrBlank()) {
        candidates.firstOrNull { it.slotSuffix == activeSlot }?.let { return it }
    }
    candidates.firstOrNull { it.slotSuffix == null }?.let { return it }
    candidates.firstOrNull { it.slotSuffix == "_a" }?.let { return it }
    return candidates.first()
}

/**
 * Grid of the six most common flash targets. Tapping an available card selects
 * the partition (active slot resolved) and immediately opens the image picker.
 */
@Composable
fun QuickPartitionsGrid(
    partitions: List<Partition>,
    activeSlot: String?,
    selectedPartition: Partition?,
    onSelect: (Partition) -> Unit,
    onUnavailable: () -> Unit,
    onBlocked: () -> Unit = onUnavailable,
    modifier: Modifier = Modifier,
) {
    val targets = quickTargets.map { target ->
        target to findCandidate(partitions, target.kind, activeSlot)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        targets.chunked(2).forEach { rowTargets ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowTargets.forEach { (target, partition) ->
                    QuickPartitionCard(
                        kind = target.kind,
                        title = stringResource(target.titleRes),
                        description = stringResource(target.descriptionRes),
                        partition = partition,
                        selected = partition != null && selectedPartition?.name == partition.name,
                        onSelect = {
                            when {
                                partition == null -> onUnavailable()
                                partition.isProtected -> onBlocked()
                                else -> onSelect(partition)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (rowTargets.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickPartitionCard(
    kind: PartitionKind,
    title: String,
    description: String,
    partition: Partition?,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = partition != null && !partition.isProtected
    val containerColor = when {
        !available -> MaterialTheme.colorScheme.surfaceContainerLowest
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = when {
        !available -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = when {
        !available -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val icon = partitionKindIcon(kind)

    Card(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(
                    icon = if (partition?.isProtected == true) Icons.Filled.Lock else icon,
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    size = 32.dp,
                    contentDescription = stringResource(R.string.quick_card_desc, title),
                )
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.monoMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = partition?.name ?: stringResource(R.string.quick_unavailable),
                style = MaterialTheme.typography.monoSmall,
                color = if (available) {
                    MaterialTheme.colorScheme.primary
                } else {
                    contentColor
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    partition == null -> stringResource(R.string.quick_unavailable_hint)
                    partition.isProtected -> stringResource(R.string.quick_blocked_hint)
                    partition.sizeBytes > 0L -> stringResource(
                        R.string.partition_size_value,
                        Formats.bytes(partition.sizeBytes),
                    )

                    else -> stringResource(R.string.quick_size_unknown)
                },
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (available) 1f else 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
