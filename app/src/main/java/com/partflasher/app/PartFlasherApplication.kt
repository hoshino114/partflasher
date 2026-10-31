package com.partflasher.app

import android.app.Application
import com.partflasher.app.core.AppContainer

/**
 * Creates the single [AppContainer] for the process.
 *
 * Deliberately does no root or partition work at startup: the su request is
 * triggered by [com.partflasher.app.viewmodel.MainViewModel] only after the UI
 * is on screen, so the authorization prompt is never shown before the user sees
 * what the app is doing.
 */
class PartFlasherApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.logRepository.info(
            "App",
            getString(R.string.log_app_started, BuildConfig.VERSION_NAME),
        )
    }
}
