package com.partflasher.app.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.partflasher.app.PartFlasherApplication
import com.partflasher.app.R
import com.partflasher.app.core.AppContainer
import com.partflasher.app.core.model.FlashRequest
import com.partflasher.app.core.model.FlashState
import com.partflasher.app.core.model.ImageFormat
import com.partflasher.app.core.model.ImageValidation
import com.partflasher.app.core.model.Partition
import com.partflasher.app.core.model.PartitionKind
import com.partflasher.app.core.model.RootState
import com.partflasher.app.core.model.RootStatus
import com.partflasher.app.core.model.StagedImage
import com.partflasher.app.core.model.ValidationIssue
import com.partflasher.app.core.model.ValidationSeverity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the entire flash workflow state for the UI.
 *
 * The ViewModel never touches the shell directly: it talks to the app-scoped
 * [AppContainer] so an in-flight flash survives activity recreation. All
 * user-visible strings that are generated here come from resources; messages
 * produced by core (root/partition/validation/flash) are surfaced verbatim.
 */
class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val container: AppContainer =
        (application as PartFlasherApplication).container
    private val app: Application = application

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var rootJob: Job? = null
    private var partitionJob: Job? = null
    private var deviceJob: Job? = null
    private var stagingJob: Job? = null

    init {
        observeLogs()
        observeFlashState()
        bootstrap()
    }

    // ---------------------------------------------------------------------
    // Bootstrap / root / device / partitions
    // ---------------------------------------------------------------------

    private fun bootstrap() {
        viewModelScope.launch {
            cleanupStagingCache()
            launch { loadDeviceInfo() }
            _state.update { it.copy(rootStatus = RootStatus.checking()) }
            val result = safeCall { container.rootManager.detect(request = true) }
            val status = result.getOrNull()
                ?: RootStatus(state = RootState.ERROR, detail = result.exceptionOrNull()?.message)
            _state.update { it.copy(rootStatus = status) }
            if (status.isGranted) {
                loadPartitions()
            }
        }
    }

    /** Top bar refresh: re-check root (no new prompt when already granted) and reload data. */
    fun refresh() {
        val current = _state.value
        if (current.isFlashing) {
            _events.tryEmit(UiEvent.FlashAlreadyRunning)
            return
        }
        if (rootJob?.isActive == true) return
        rootJob = viewModelScope.launch {
            val wasGranted = current.rootGranted
            _state.update {
                it.copy(
                    rootStatus = if (wasGranted) {
                        it.rootStatus.copy(state = RootState.CHECKING)
                    } else {
                        RootStatus.checking()
                    },
                    scanError = null,
                )
            }
            val result = safeCall {
                if (wasGranted) {
                    container.rootManager.refresh()
                } else {
                    container.rootManager.detect(request = true)
                }
            }
            val status = result.getOrNull()
                ?: RootStatus(state = RootState.ERROR, detail = result.exceptionOrNull()?.message)
            _state.update { it.copy(rootStatus = status) }
            if (status.isGranted) {
                if (_state.value.deviceInfo == null) loadDeviceInfo()
                loadPartitions()
            }
        }
    }

    /** "重新检测": always triggers a fresh su prompt. */
    fun retryRoot() {
        if (_state.value.isFlashing) {
            _events.tryEmit(UiEvent.FlashAlreadyRunning)
            return
        }
        if (rootJob?.isActive == true) return
        rootJob = viewModelScope.launch {
            _state.update { it.copy(rootStatus = RootStatus.checking()) }
            val result = safeCall { container.rootManager.detect(request = true) }
            val status = result.getOrNull()
                ?: RootStatus(state = RootState.ERROR, detail = result.exceptionOrNull()?.message)
            _state.update { it.copy(rootStatus = status) }
            if (status.isGranted) {
                if (_state.value.deviceInfo == null) loadDeviceInfo()
                loadPartitions()
            }
        }
    }

    private fun loadDeviceInfo() {
        if (deviceJob?.isActive == true) return
        deviceJob = viewModelScope.launch {
            _state.update { it.copy(deviceLoading = true, deviceError = null) }
            val result = safeCall { withContext(Dispatchers.IO) { container.deviceInfoProvider.load() } }
            val info = result.getOrNull()
            _state.update {
                it.copy(
                    deviceInfo = info ?: it.deviceInfo,
                    deviceLoading = false,
                    deviceError = result.exceptionOrNull()?.let { error ->
                        str(R.string.device_load_failed, error.message ?: str(R.string.common_unknown))
                    },
                )
            }
        }
    }

    private fun loadPartitions() {
        if (partitionJob?.isActive == true) return
        partitionJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, scanError = null) }
            val result = safeCall { withContext(Dispatchers.IO) { container.partitionRepository.load() } }
            val scan = result.getOrNull()
            if (scan == null) {
                _state.update {
                    it.copy(
                        scanning = false,
                        scanError = str(
                            R.string.partitions_load_failed,
                            result.exceptionOrNull()?.message ?: str(R.string.common_unknown),
                        ),
                    )
                }
                return@launch
            }
            _state.update { current ->
                // Re-bind the selection to the refreshed partition instance so
                // resolved block paths stay in sync after a re-scan.
                val refreshedSelection = current.selectedPartition?.let { old ->
                    scan.partitions.firstOrNull { it.name == old.name }
                }
                val withScan = current.copy(
                    scan = scan,
                    scanning = false,
                    scanError = null,
                    // Drop a selection that no longer exists in the scan table:
                    // the UI must never offer a target core cannot re-verify.
                    selectedPartition = refreshedSelection,
                )
                // If an image was staged before the partition table was ready,
                // pick the smart target now that candidates are known.
                val target = withScan.selectedPartition
                    ?: withScan.stagedImage?.let { smartSelectPartition(it, withScan) }
                withScan.copy(selectedPartition = target)
            }
            revalidate()
        }
    }

    // ---------------------------------------------------------------------
    // Logs
    // ---------------------------------------------------------------------

    private fun observeLogs() {
        viewModelScope.launch {
            container.logRepository.entries.collect { entries ->
                _state.update { it.copy(logs = entries) }
            }
        }
    }

    fun setLogFilter(filter: LogLevelFilter) {
        _state.update { it.copy(logFilter = filter) }
    }

    fun setLogQuery(query: String) {
        _state.update { it.copy(logQuery = query) }
    }

    fun clearLogs() {
        container.logRepository.clear()
        _state.update { it.copy(logs = emptyList()) }
    }

    fun copyLogs() {
        val text = container.logRepository.snapshotText()
        if (text.isBlank()) return
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(app.getString(R.string.app_name), text),
        )
        _events.tryEmit(UiEvent.LogsCopied)
    }

    fun exportLogs(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = container.logRepository.snapshotText()
                val output = app.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("openOutputStream returned null")
                output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: textFileNameFallback()
                _events.emit(UiEvent.LogsExported(name))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _events.emit(UiEvent.ExportFailed(t.message))
            }
        }
    }

    private fun textFileNameFallback(): String = app.getString(R.string.app_name) + ".txt"

    // ---------------------------------------------------------------------
    // Selection / image staging / validation
    // ---------------------------------------------------------------------

    fun selectPartition(partition: Partition) {
        if (_state.value.isFlashing) return
        if (partition.isProtected) return
        _state.update { it.copy(selectedPartition = partition) }
        revalidate()
    }

    fun clearSelection() {
        if (_state.value.isFlashing) return
        _state.update { it.copy(selectedPartition = null, validation = null, validating = false) }
    }

    /**
     * Stages [uri] and, when no partition is selected yet, smart-selects a
     * candidate from the image name/format. Any previous staging job is
     * cancelled and the previous staged file removed so rotation or rapid
     * re-picks cannot leave two operations running.
     */
    fun openImage(uri: Uri) {
        if (_state.value.isFlashing) {
            _events.tryEmit(UiEvent.FlashAlreadyRunning)
            return
        }
        stagingJob?.cancel()
        _state.value.stagedImage?.let { deleteStagedFile(it) }
        _state.update {
            it.copy(
                stagedImage = null,
                staging = true,
                stagingProgress = 0f,
                stagingError = null,
                validation = null,
                validating = false,
            )
        }
        stagingJob = viewModelScope.launch {
            try {
                val image = container.imageStager.stage(uri) { progress ->
                    _state.update { current ->
                        if (current.staging) current.copy(stagingProgress = progress) else current
                    }
                }
                _state.update { current ->
                    val target = current.selectedPartition
                        ?: smartSelectPartition(image, current)
                    current.copy(
                        stagedImage = image,
                        staging = false,
                        stagingProgress = 1f,
                        stagingError = null,
                        selectedPartition = target,
                    )
                }
                revalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        stagedImage = null,
                        staging = false,
                        stagingProgress = 0f,
                        stagingError = str(
                            R.string.snack_image_failed,
                            t.message ?: str(R.string.common_unknown),
                        ),
                        validation = null,
                    )
                }
            }
        }
    }

    fun clearImage() {
        if (_state.value.isFlashing) return
        stagingJob?.cancel()
        _state.value.stagedImage?.let { deleteStagedFile(it) }
        _state.update {
            it.copy(
                stagedImage = null,
                staging = false,
                stagingProgress = 0f,
                stagingError = null,
                validation = null,
                validating = false,
            )
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    /**
     * Picks a sensible default target for a freshly staged image:
     * init_boot/vendor_boot/dtbo/vbmeta/recovery by file name, boot by header.
     * Prefers the active slot for A/B devices.
     */
    private fun smartSelectPartition(image: StagedImage, state: MainUiState): Partition? {
        val fileName = image.displayName.lowercase(Locale.ROOT)
        val kind = when {
            fileName.contains("init_boot") -> PartitionKind.INIT_BOOT
            fileName.contains("vendor_boot") -> PartitionKind.VENDOR_BOOT
            fileName.contains("dtbo") -> PartitionKind.DTBO
            fileName.contains("vbmeta") -> PartitionKind.VBMETA
            fileName.contains("recovery") -> PartitionKind.RECOVERY
            image.format == ImageFormat.VENDOR_BOOT -> PartitionKind.VENDOR_BOOT
            image.format == ImageFormat.DTBO -> PartitionKind.DTBO
            image.format == ImageFormat.VBMETA -> PartitionKind.VBMETA
            image.format == ImageFormat.ANDROID_BOOT -> PartitionKind.BOOT
            fileName.contains("boot") -> PartitionKind.BOOT
            else -> null
        } ?: return null

        val candidates = state.partitions.filter { it.kind == kind && !it.isProtected }
        return candidates.firstOrNull { it.slotSuffix != null && it.slotSuffix == state.activeSlot }
            ?: candidates.firstOrNull { it.slotSuffix == null }
            ?: candidates.firstOrNull()
    }

    private fun revalidate() {
        val current = _state.value
        val partition = current.selectedPartition
        val image = current.stagedImage
        if (partition == null || image == null) {
            _state.update { it.copy(validation = null, validating = false) }
            return
        }
        _state.update { it.copy(validating = true) }
        val validation = try {
            container.imageValidator.validate(
                partition = partition,
                image = image,
                activeSlot = current.activeSlot,
                deviceInfo = current.deviceInfo,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ImageValidation(
                issues = listOf(
                    ValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        code = "internal_validation",
                        message = str(
                            R.string.validation_failed,
                            t.message ?: str(R.string.common_unknown),
                        ),
                    ),
                ),
                canFlash = false,
            )
        }
        _state.update { latest ->
            // Ignore late results if the user changed the pair meanwhile.
            val samePair = latest.selectedPartition?.name == partition.name &&
                latest.stagedImage?.stagedPath == image.stagedPath
            if (samePair) {
                latest.copy(validation = validation, validating = false)
            } else {
                latest.copy(validating = false)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Flash
    // ---------------------------------------------------------------------

    private fun observeFlashState() {
        viewModelScope.launch {
            container.flashController.state.collect { flash ->
                val previous = _state.value.flashState
                val becameTerminal = (flash is FlashState.Success || flash is FlashState.Failed) &&
                    previous !is FlashState.Success && previous !is FlashState.Failed
                _state.update { current ->
                    current.copy(
                        flashState = flash,
                        flashResultVisible = current.flashResultVisible || becameTerminal,
                    )
                }
                if (becameTerminal) {
                    _events.tryEmit(UiEvent.FlashFinished(flash is FlashState.Success))
                }
            }
        }
    }

    fun requestConfirm() {
        if (_state.value.canFlash) {
            _state.update { it.copy(confirmVisible = true) }
        }
    }

    fun dismissConfirm() {
        _state.update { it.copy(confirmVisible = false) }
    }

    /**
     * Starts the flash exactly once. The confirm dialog is only closed after the
     * controller accepted the request; a second tap while running is ignored.
     */
    fun startFlash(createBackup: Boolean, verifyAfterWrite: Boolean) {
        val current = _state.value
        val partition = current.selectedPartition
        val image = current.stagedImage
        if (partition == null || image == null || !current.canFlash) {
            _events.tryEmit(UiEvent.NotReadyToFlash)
            return
        }
        if (container.flashController.isRunning) {
            _events.tryEmit(UiEvent.FlashAlreadyRunning)
            return
        }
        val request = FlashRequest(
            partition = partition,
            image = image,
            createBackup = createBackup,
            verifyAfterWrite = verifyAfterWrite,
        )
        val started = try {
            container.flashController.start(request)
        } catch (t: Throwable) {
            false
        }
        if (started) {
            _state.update {
                it.copy(
                    confirmVisible = false,
                    flashResultVisible = false,
                    flashTargetName = partition.name,
                )
            }
        } else {
            _events.tryEmit(UiEvent.FlashAlreadyRunning)
        }
    }

    fun dismissFlashResult() {
        _state.update { it.copy(flashResultVisible = false, flashTargetName = null) }
        if (!container.flashController.isRunning) {
            container.flashController.reset()
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun deleteStagedFile(image: StagedImage) {
        runCatching { File(image.stagedPath).delete() }
    }

    private fun cleanupStagingCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.imageStager.cleanupStale() }
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)

    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
