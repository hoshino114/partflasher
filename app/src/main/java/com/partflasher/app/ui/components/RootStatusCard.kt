package com.partflasher.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.partflasher.app.R
import com.partflasher.app.core.model.RootSolution
import com.partflasher.app.core.model.RootState
import com.partflasher.app.core.model.RootStatus
import com.partflasher.app.ui.theme.statusColors
import com.partflasher.app.ui.util.Formats
import com.partflasher.app.ui.util.rootSolutionLabel
import androidx.compose.foundation.layout.fillMaxWidth
import com.partflasher.app.ui.theme.monoSmall

/**
 * Root permission status: solution chip, version, uid, su path and an
 * expandable technical detail section. Denied / missing root states carry
 * actionable guidance and a primary retry action that re-triggers the su prompt.
 */
@Composable
fun RootStatusCard(
    status: RootStatus,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = onRetry,
    modifier: Modifier = Modifier,
) {
    val isChecking = status.state == RootState.CHECKING
    var expanded by rememberSaveable { mutableStateOf(false) }

    val titleRes = when (status.state) {
        RootState.CHECKING -> R.string.root_title_checking
        RootState.GRANTED -> R.string.root_title_granted
        RootState.DENIED -> R.string.root_title_denied
        RootState.NOT_ROOTED -> R.string.root_title_not_rooted
        RootState.ERROR -> R.string.root_title_error
        RootState.UNKNOWN -> R.string.root_title_unknown
    }
    val detailRes = when (status.state) {
        RootState.CHECKING -> R.string.root_checking_detail
        RootState.GRANTED -> R.string.root_granted_detail
        RootState.DENIED -> R.string.root_denied_detail
        RootState.NOT_ROOTED -> R.string.root_not_rooted_detail
        RootState.ERROR -> R.string.root_error_detail
        RootState.UNKNOWN -> R.string.root_unknown_detail
    }

    val accentColor = when (status.state) {
        RootState.GRANTED -> MaterialTheme.statusColors.success
        RootState.DENIED, RootState.ERROR -> MaterialTheme.colorScheme.error
        RootState.NOT_ROOTED -> MaterialTheme.statusColors.warning
        RootState.CHECKING, RootState.UNKNOWN -> MaterialTheme.colorScheme.primary
    }
    val accentContainer = when (status.state) {
        RootState.GRANTED -> MaterialTheme.statusColors.successContainer
        RootState.DENIED, RootState.ERROR -> MaterialTheme.colorScheme.errorContainer
        RootState.NOT_ROOTED -> MaterialTheme.statusColors.warningContainer
        RootState.CHECKING, RootState.UNKNOWN -> MaterialTheme.colorScheme.primaryContainer
    }
    val icon: ImageVector? = when (status.state) {
        RootState.GRANTED -> Icons.Filled.VerifiedUser
        RootState.DENIED -> Icons.Filled.Lock
        RootState.NOT_ROOTED -> Icons.Filled.WarningAmber
        RootState.ERROR -> Icons.Filled.ErrorOutline
        RootState.UNKNOWN -> Icons.Filled.Security
        RootState.CHECKING -> null
    }

    val showDetails = status.state != RootState.UNKNOWN && (
        status.solution != RootSolution.UNKNOWN ||
            !status.version.isNullOrBlank() ||
            status.uid != null ||
            !status.suPath.isNullOrBlank() ||
            status.checkedAtMillis > 0L ||
            !status.message.isNullOrBlank() ||
            !status.detail.isNullOrBlank()
        )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = when (status.state) {
                RootState.DENIED, RootState.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                RootState.GRANTED -> MaterialTheme.statusColors.success.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = accentContainer,
                    contentColor = accentColor,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = stringResource(R.string.root_icon_desc),
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = accentColor,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (status.state == RootState.GRANTED) {
                        val solutionText = if (
                            status.solution != RootSolution.UNKNOWN &&
                            status.solution != RootSolution.NONE
                        ) {
                            rootSolutionLabel(status.solution)
                        } else {
                            null
                        }
                        val subtitle = listOfNotNull(solutionText, status.version)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                        Text(
                            text = if (subtitle.isNotBlank()) {
                                subtitle
                            } else {
                                stringResource(R.string.root_card_title)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (!isChecking) {
                        Text(
                            text = stringResource(R.string.action_retry_root),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!isChecking) {
                    IconButton(
                        onClick = if (status.state == RootState.GRANTED) onRefresh else onRetry,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.root_retry_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = stringResource(detailRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (status.state == RootState.GRANTED) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(
                        text = rootSolutionLabel(status.solution),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (!status.version.isNullOrBlank()) {
                        StatusBadge(text = status.version)
                    }
                    StatusBadge(
                        text = stringResource(R.string.common_success),
                        containerColor = MaterialTheme.statusColors.successContainer,
                        contentColor = MaterialTheme.statusColors.onSuccessContainer,
                    )
                }
            }

            AnimatedVisibility(visible = expanded && showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (status.solution != RootSolution.UNKNOWN) {
                        LabelValueRow(
                            label = stringResource(R.string.root_solution_label),
                            value = rootSolutionLabel(status.solution),
                        )
                    }
                    if (!status.version.isNullOrBlank()) {
                        LabelValueRow(
                            label = stringResource(R.string.root_version_label),
                            value = status.version.orEmpty(),
                        )
                    }
                    status.uid?.let {
                        LabelValueRow(
                            label = stringResource(R.string.root_uid_label),
                            value = it.toString(),
                            mono = true,
                        )
                    }
                    if (!status.suPath.isNullOrBlank()) {
                        LabelValueRow(
                            label = stringResource(R.string.root_su_path_label),
                            value = status.suPath.orEmpty(),
                            mono = true,
                            selectable = true,
                        )
                    }
                    if (status.checkedAtMillis > 0L) {
                        LabelValueRow(
                            label = stringResource(R.string.root_checked_at_label),
                            value = Formats.dateTime(status.checkedAtMillis),
                        )
                    }
                    if (!status.message.isNullOrBlank()) {
                        LabelValueRow(
                            label = stringResource(R.string.root_detail_message_label),
                            value = status.message,
                        )
                    }
                    if (!status.detail.isNullOrBlank()) {
                        Spacer(Modifier.size(4.dp))
                        MonoText(
                            text = status.detail,
                            style = MaterialTheme.typography.monoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectable = true,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showDetails) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            text = stringResource(
                                if (expanded) R.string.action_collapse else R.string.root_detail_expand,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!isChecking && status.state != RootState.GRANTED) {
                    FilledTonalButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_retry_root))
                    }
                }
            }
            if (!isChecking && status.state != RootState.GRANTED) {
                Text(
                    text = stringResource(R.string.root_retry_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
