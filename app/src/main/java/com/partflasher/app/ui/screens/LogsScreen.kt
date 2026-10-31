package com.partflasher.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.partflasher.app.R
import com.partflasher.app.core.model.LogEntry
import com.partflasher.app.core.model.LogLevel
import com.partflasher.app.ui.components.EmptyState
import com.partflasher.app.ui.components.SectionHeader
import com.partflasher.app.ui.util.Formats
import com.partflasher.app.ui.util.logLevelColor
import com.partflasher.app.ui.util.logLevelLabel
import com.partflasher.app.viewmodel.LogLevelFilter
import com.partflasher.app.viewmodel.MainUiState
import com.partflasher.app.ui.theme.monoBody
import com.partflasher.app.ui.theme.monoSmall

private data class FilterOption(val filter: LogLevelFilter, @param:StringRes val labelRes: Int)

private val filterOptions = listOf(
    FilterOption(LogLevelFilter.ALL, R.string.logs_filter_all),
    FilterOption(LogLevelFilter.COMMAND, R.string.logs_filter_command),
    FilterOption(LogLevelFilter.INFO, R.string.logs_filter_info),
    FilterOption(LogLevelFilter.SUCCESS, R.string.logs_filter_success),
    FilterOption(LogLevelFilter.WARN, R.string.logs_filter_warn),
    FilterOption(LogLevelFilter.ERROR, R.string.logs_filter_error),
    FilterOption(LogLevelFilter.DEBUG, R.string.logs_filter_debug),
)

/**
 * "日志" destination: level chips, search, monospace auto-scrolling list with
 * copy / export (SAF) / clear actions.
 */
@Composable
fun LogsScreen(
    state: MainUiState,
    onFilterChange: (LogLevelFilter) -> Unit,
    onQueryChange: (String) -> Unit,
    onCopy: () -> Unit,
    onExport: (Uri) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            lastVisible == null || lastVisible.index >= info.totalItemsCount - 2
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) onExport(uri)
    }

    val filteredLogs = remember(state.logs, state.logFilter, state.logQuery) {
        val query = state.logQuery.trim()
        state.logs.filter { entry ->
            state.logFilter.matches(entry) &&
                (query.isEmpty() ||
                    entry.message.contains(query, ignoreCase = true) ||
                    entry.tag.contains(query, ignoreCase = true))
        }
    }

    // Auto-scroll to the newest entry only while the user is already at the
    // bottom, so reading older lines is never interrupted.
    LaunchedEffect(filteredLogs.size, isAtBottom) {
        if (filteredLogs.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(
                title = stringResource(R.string.logs_title),
                subtitle = if (state.logs.isEmpty()) {
                    null
                } else if (filteredLogs.size == state.logs.size) {
                    stringResource(R.string.logs_count, state.logs.size)
                } else {
                    stringResource(R.string.logs_filtered_count, filteredLogs.size, state.logs.size)
                },
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filterOptions.forEach { option ->
                    FilterChip(
                        selected = state.logFilter == option.filter,
                        onClick = { onFilterChange(option.filter) },
                        label = { Text(stringResource(option.labelRes)) },
                    )
                }
            }

            OutlinedTextField(
                value = state.logQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                placeholder = { Text(stringResource(R.string.logs_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.action_search),
                    )
                },
                trailingIcon = if (state.logQuery.isNotBlank()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val hasLogs = state.logs.isNotEmpty()
                FilledTonalButton(
                    onClick = onCopy,
                    enabled = hasLogs,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.logs_copy_desc),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.logs_copy_all),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
                FilledTonalButton(
                    onClick = { exportLauncher.launch(Formats.logFileName(System.currentTimeMillis())) },
                    enabled = hasLogs,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.FileDownload,
                        contentDescription = stringResource(R.string.logs_export_desc),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.logs_export),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
                FilledTonalButton(
                    onClick = { showClearDialog = true },
                    enabled = hasLogs,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.logs_clear_desc),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.logs_clear),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                EmptyState(
                    icon = if (state.logs.isEmpty()) Icons.Filled.Terminal else Icons.Filled.Search,
                    title = if (state.logs.isEmpty()) {
                        stringResource(R.string.logs_empty_title)
                    } else {
                        stringResource(R.string.logs_empty_filter)
                    },
                    body = if (state.logs.isEmpty()) {
                        stringResource(R.string.logs_empty_body)
                    } else {
                        stringResource(R.string.logs_search_placeholder)
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(filteredLogs, key = { it.id }) { entry ->
                        LogEntryRow(entry = entry)
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.logs_clear_confirm_title)) },
            text = { Text(stringResource(R.string.logs_clear_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        onClear()
                    },
                ) {
                    Text(stringResource(R.string.logs_clear_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = MaterialTheme.shapes.large,
        )
    }
}

/**
 * One log line. Command lines get a tinted monospace block so users can copy
 * them into a terminal; other levels are color-coded by severity.
 */
@Composable
private fun LogEntryRow(entry: LogEntry) {
    val levelColor = logLevelColor(entry.level)
    val isCommand = entry.level == LogLevel.COMMAND
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isCommand) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Formats.logTime(entry.timestampMillis),
                    style = MaterialTheme.typography.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = logLevelLabel(entry.level),
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor,
                    modifier = Modifier.width(44.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.monoBody,
                fontFamily = FontFamily.Monospace,
                color = if (isCommand) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
