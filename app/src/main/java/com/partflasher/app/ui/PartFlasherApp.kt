package com.partflasher.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.partflasher.app.R
import com.partflasher.app.ui.components.ConfirmFlashDialog
import com.partflasher.app.ui.components.FlashProgressDialog
import com.partflasher.app.ui.components.FlashResultDialog
import com.partflasher.app.ui.screens.FlashScreen
import com.partflasher.app.ui.screens.GuideScreen
import com.partflasher.app.ui.screens.LogsScreen
import com.partflasher.app.ui.util.activeSlotLabel
import com.partflasher.app.viewmodel.MainUiState
import com.partflasher.app.viewmodel.MainViewModel
import com.partflasher.app.viewmodel.UiEvent
import kotlinx.coroutines.launch

private data class AppTab(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val appTabs = listOf(
    AppTab(R.string.nav_flash, Icons.Filled.FlashOn),
    AppTab(R.string.nav_logs, Icons.AutoMirrored.Filled.ReceiptLong),
    AppTab(R.string.nav_guide, Icons.AutoMirrored.Filled.MenuBook),
)

/**
 * Root composable: Material 3 scaffold with the three destinations
 * (刷入 / 日志 / 指南), snackbar host, image picker and the flash dialog flow.
 * Selected tab survives configuration changes via `rememberSaveable`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartFlasherApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val showMessage: (String) -> Unit = remember(snackbarHostState) {
        { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    // Pre-resolve one-shot snackbar copy so the event collector stays cheap.
    val logsCopiedText = stringResource(R.string.snack_logs_copied)
    val flashRunningText = stringResource(R.string.snack_flash_already_running)
    val notReadyText = stringResource(R.string.snack_not_ready)
    val flashFinishedSuccessText = stringResource(R.string.snack_flash_finished_success)
    val flashFinishedFailureText = stringResource(R.string.snack_flash_finished_failure)
    val logsClearedText = stringResource(R.string.snack_logs_cleared)
    val unknownText = stringResource(R.string.common_unknown)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                UiEvent.LogsCopied -> logsCopiedText
                is UiEvent.LogsExported ->
                    context.getString(R.string.snack_logs_exported, event.fileName)

                is UiEvent.ExportFailed ->
                    context.getString(
                        R.string.snack_logs_export_failed,
                        event.reason ?: unknownText,
                    )

                UiEvent.FlashAlreadyRunning -> flashRunningText
                UiEvent.NotReadyToFlash -> notReadyText
                is UiEvent.FlashFinished -> if (event.success) {
                    flashFinishedSuccessText
                } else {
                    flashFinishedFailureText
                }
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.openImage(uri)
    }
    val launchPicker: () -> Unit = { imagePicker.launch(arrayOf("*/*")) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            PartFlasherTopBar(
                state = state,
                onRefresh = viewModel::refresh,
                onOpenLogs = { selectedTab = 1 },
            )
        },
        bottomBar = {
            NavigationBar {
                appTabs.forEachIndexed { index, tab ->
                    val selected = selectedTab == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = index },
                        icon = {
                            if (index == 1 && state.errorLogCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge {
                                            Text(
                                                text = state.errorLogCount.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    },
                                ) {
                                    Icon(imageVector = tab.icon, contentDescription = null)
                                }
                            } else {
                                Icon(imageVector = tab.icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                0 -> FlashScreen(
                    state = state,
                    onRetryRoot = viewModel::retryRoot,
                    onRefresh = viewModel::refresh,
                    onSelectPartition = viewModel::selectPartition,
                    onQuickSelect = { partition ->
                        viewModel.selectPartition(partition)
                        launchPicker()
                    },
                    onPickImage = launchPicker,
                    onClearImage = viewModel::clearImage,
                    onClearSelection = viewModel::clearSelection,
                    onFlashClick = viewModel::requestConfirm,
                    onSearchChange = viewModel::setSearchQuery,
                    onShowMessage = showMessage,
                )

                1 -> LogsScreen(
                    state = state,
                    onFilterChange = viewModel::setLogFilter,
                    onQueryChange = viewModel::setLogQuery,
                    onCopy = viewModel::copyLogs,
                    onExport = viewModel::exportLogs,
                    onClear = {
                        viewModel.clearLogs()
                        showMessage(logsClearedText)
                    },
                )

                else -> GuideScreen()
            }
        }
    }

    val partition = state.selectedPartition
    val image = state.stagedImage
    if (state.confirmVisible && partition != null && image != null) {
        ConfirmFlashDialog(
            partition = partition,
            image = image,
            activeSlot = state.activeSlot,
            requiresExtraConfirm = state.validation?.requiresExtraConfirm == true,
            onDismiss = viewModel::dismissConfirm,
            onConfirm = viewModel::startFlash,
        )
    }
    if (state.isFlashing) {
        FlashProgressDialog(state = state)
    }
    if (state.flashResultVisible && state.isTerminalFlashState) {
        FlashResultDialog(
            state = state,
            onDismiss = viewModel::dismissFlashResult,
            onViewLogs = {
                viewModel.dismissFlashResult()
                selectedTab = 1
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartFlasherTopBar(
    state: MainUiState,
    onRefresh: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val device = state.deviceInfo
    val subtitle: String = when {
        device == null -> stringResource(R.string.device_loading)
        else -> {
            val model = device.displayName.takeIf { it.isNotBlank() }
            val android = if (device.androidRelease.isNotBlank()) {
                stringResource(R.string.device_android_platform, device.androidRelease)
            } else {
                null
            }
            val slot = if (device.activeSlot != null) activeSlotLabel(device.activeSlot) else null
            listOfNotNull(model, android, slot).joinToString(" · ")
        }
    }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(
                onClick = onRefresh,
                enabled = !state.isFlashing,
                modifier = Modifier.size(48.dp),
            ) {
                if (state.scanning || state.rootChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_refresh_desc),
                    )
                }
            }
            IconButton(
                onClick = onOpenLogs,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = stringResource(R.string.action_logs_desc),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
