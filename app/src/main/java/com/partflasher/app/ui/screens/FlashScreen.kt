package com.partflasher.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.partflasher.app.R
import com.partflasher.app.core.model.Partition
import com.partflasher.app.ui.components.DeviceInfoCard
import com.partflasher.app.ui.components.EmptyState
import com.partflasher.app.ui.components.InlineLoading
import com.partflasher.app.ui.components.MessageBlock
import com.partflasher.app.ui.components.PartitionRow
import com.partflasher.app.ui.components.QuickPartitionsGrid
import com.partflasher.app.ui.components.RootStatusCard
import com.partflasher.app.ui.components.SafetyBanner
import com.partflasher.app.ui.components.SectionHeader
import com.partflasher.app.ui.components.SelectionCard
import com.partflasher.app.ui.util.bootFamilySorted
import com.partflasher.app.ui.util.filterPartitions
import com.partflasher.app.ui.util.otherPartitionsSorted
import com.partflasher.app.viewmodel.MainUiState

/**
 * Main "刷入" destination: root status, device, safety banner, selection summary,
 * quick partition cards and the searchable partition table.
 */
@Composable
fun FlashScreen(
    state: MainUiState,
    onRetryRoot: () -> Unit,
    onRefresh: () -> Unit,
    onSelectPartition: (Partition) -> Unit,
    onQuickSelect: (Partition) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onClearSelection: () -> Unit,
    onFlashClick: () -> Unit,
    onSearchChange: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Layout indices for the fixed top sections; used for gentle auto-scroll so
    // the review card stays visible after the user picks a partition/image.
    val summaryIndex = 3
    var previousSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var previousImage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.selectedPartition?.name) {
        val current = state.selectedPartition?.name
        if (current != null && current != previousSelection &&
            listState.firstVisibleItemIndex > summaryIndex
        ) {
            listState.animateScrollToItem(summaryIndex)
        }
        previousSelection = current
    }
    LaunchedEffect(state.stagedImage?.stagedPath) {
        val current = state.stagedImage?.stagedPath
        if (current != null && current != previousImage &&
            listState.firstVisibleItemIndex > summaryIndex
        ) {
            listState.animateScrollToItem(summaryIndex)
        }
        previousImage = current
    }

    val filteredPartitions = remember(state.scan, state.searchQuery) {
        state.partitions.filterPartitions(state.searchQuery)
    }
    val bootFamily = remember(filteredPartitions) { filteredPartitions.bootFamilySorted() }
    val others = remember(filteredPartitions) { filteredPartitions.otherPartitionsSorted() }

    val quickUnavailableHint = stringResource(R.string.quick_unavailable_hint)
    val quickBlockedHint = stringResource(R.string.quick_blocked_hint)
    val protectedFallback = stringResource(R.string.partition_protected)
    val scan = state.scan
    val scanError = state.scanError
    val partitionsSubtitle = if (scan != null) {
        val countText = stringResource(R.string.partitions_count, scan.partitions.size)
        val sourceText = if (scan.sourceDirectory != null) {
            stringResource(R.string.partitions_source, scan.sourceDirectory)
        } else {
            null
        }
        if (sourceText != null) "$countText · $sourceText" else countText
    } else {
        stringResource(R.string.partitions_subtitle)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "root") {
                RootStatusCard(
                    status = state.rootStatus,
                    onRetry = onRetryRoot,
                    onRefresh = onRefresh,
                )
            }
            item(key = "device") {
                DeviceInfoCard(
                    deviceInfo = state.deviceInfo,
                    loading = state.deviceLoading,
                    error = state.deviceError,
                )
            }
            item(key = "safety") { SafetyBanner() }
            item(key = "selection") {
                SelectionCard(
                    state = state,
                    onPickImage = onPickImage,
                    onClearImage = onClearImage,
                    onClearSelection = onClearSelection,
                    onFlashClick = onFlashClick,
                )
            }

            item(key = "quick_header") {
                SectionHeader(
                    title = stringResource(R.string.quick_title),
                    subtitle = stringResource(R.string.quick_subtitle),
                )
            }
            item(key = "quick_grid") {
                QuickPartitionsGrid(
                    partitions = state.partitions,
                    activeSlot = state.activeSlot,
                    selectedPartition = state.selectedPartition,
                    onSelect = onQuickSelect,
                    onUnavailable = { onShowMessage(quickUnavailableHint) },
                    onBlocked = { onShowMessage(quickBlockedHint) },
                )
            }

            item(key = "partitions_header") {
                SectionHeader(
                    title = stringResource(R.string.partitions_title),
                    subtitle = partitionsSubtitle,
                    trailing = {
                        if (state.scanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
            }
            item(key = "search") {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    placeholder = { Text(stringResource(R.string.partitions_search_placeholder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.action_search),
                        )
                    },
                    trailingIcon = if (state.searchQuery.isNotBlank()) {
                        {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_clear_search),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
            }

            if (scan != null && scan.warnings.isNotEmpty()) {
                item(key = "scan_warnings") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        scan.warnings.take(3).forEach { warning ->
                            MessageBlock(
                                text = warning,
                                icon = Icons.Filled.WarningAmber,
                                iconContentDescription = stringResource(R.string.status_icon_warning),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }

            if (state.scanning) {
                item(key = "scanning") {
                    InlineLoading(text = stringResource(R.string.partitions_scanning))
                }
            }
            if (scanError != null) {
                item(key = "scan_error") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MessageBlock(
                            text = scanError,
                            icon = Icons.Filled.ErrorOutline,
                            iconContentDescription = stringResource(R.string.status_icon_error),
                        )
                        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.partitions_retry))
                        }
                    }
                }
            }

            if (state.scan == null && !state.scanning && scanError == null) {
                item(key = "partitions_pending") {
                    EmptyState(
                        icon = Icons.Filled.Storage,
                        title = stringResource(R.string.partitions_empty),
                        body = stringResource(R.string.partitions_empty_hint),
                    )
                }
            } else if (state.scan != null) {
                if (filteredPartitions.isEmpty()) {
                    item(key = "partitions_empty") {
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = if (state.searchQuery.isNotBlank()) {
                                stringResource(R.string.partitions_empty_search)
                            } else {
                                stringResource(R.string.partitions_empty)
                            },
                            body = if (state.searchQuery.isNotBlank()) {
                                stringResource(R.string.partitions_search_placeholder)
                            } else {
                                stringResource(R.string.partitions_empty_hint)
                            },
                        )
                    }
                }
                if (bootFamily.isNotEmpty()) {
                    item(key = "group_boot") {
                        SectionHeader(
                            title = stringResource(R.string.group_boot_family),
                            subtitle = stringResource(R.string.partitions_count, bootFamily.size),
                        )
                    }
                    items(bootFamily, key = { "partition_${it.name}" }) { partition ->
                        PartitionListRow(
                            partition = partition,
                            selected = state.selectedPartition?.name == partition.name,
                            onSelect = onSelectPartition,
                            onShowMessage = onShowMessage,
                            protectedFallback = protectedFallback,
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "group_other") {
                        Spacer(Modifier.height(4.dp))
                        SectionHeader(
                            title = stringResource(R.string.group_other),
                            subtitle = stringResource(R.string.partitions_count, others.size),
                        )
                    }
                    items(others, key = { "partition_${it.name}" }) { partition ->
                        PartitionListRow(
                            partition = partition,
                            selected = state.selectedPartition?.name == partition.name,
                            onSelect = onSelectPartition,
                            onShowMessage = onShowMessage,
                            protectedFallback = protectedFallback,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PartitionListRow(
    partition: Partition,
    selected: Boolean,
    onSelect: (Partition) -> Unit,
    onShowMessage: (String) -> Unit,
    protectedFallback: String,
) {
    val selectedMessage = stringResource(R.string.snack_partition_selected, partition.name)
    PartitionRow(
        partition = partition,
        selected = selected,
        onClick = {
            if (partition.isProtected) {
                onShowMessage(partition.protectedReason?.takeIf { it.isNotBlank() } ?: protectedFallback)
            } else {
                onSelect(partition)
                onShowMessage(selectedMessage)
            }
        },
    )
}
