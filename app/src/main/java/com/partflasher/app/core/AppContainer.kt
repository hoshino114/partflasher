package com.partflasher.app.core

import android.content.Context
import com.partflasher.app.core.device.DeviceInfoProvider
import com.partflasher.app.core.flash.FlashController
import com.partflasher.app.core.image.ImageInspector
import com.partflasher.app.core.image.ImageStager
import com.partflasher.app.core.log.FileLogSink
import com.partflasher.app.core.log.LogRepository
import com.partflasher.app.core.partition.PartitionRepository
import com.partflasher.app.core.root.AndroidCommandRunner
import com.partflasher.app.core.root.RootManager
import com.partflasher.app.core.validation.ImageValidator

/** Application-wide singletons. Created once by the Application class. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val logRepository = LogRepository(FileLogSink(appContext))
    val commandRunner = AndroidCommandRunner()
    val rootManager = RootManager(commandRunner, logRepository)
    val partitionRepository = PartitionRepository(rootManager, logRepository)
    val imageInspector = ImageInspector()
    val imageStager = ImageStager(appContext, logRepository, imageInspector)
    val imageValidator = ImageValidator()
    val deviceInfoProvider = DeviceInfoProvider(commandRunner)
    val flashController = FlashController(
        context = appContext,
        rootManager = rootManager,
        partitionRepository = partitionRepository,
        log = logRepository,
    )

    fun shutdown() {
        flashController.shutdown()
    }
}
