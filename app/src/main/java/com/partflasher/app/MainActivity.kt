package com.partflasher.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.partflasher.app.ui.PartFlasherApp
import com.partflasher.app.ui.theme.PartFlasherTheme
import com.partflasher.app.viewmodel.MainViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PartFlasherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PartFlasherApp(viewModel = viewModel)
                }
            }
        }

        // Keep the display awake while a write is in progress. The flag is tied
        // to the app-scoped controller state, so it survives a rotation.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state
                    .map { it.isFlashing }
                    .distinctUntilChanged()
                    .collect { flashing ->
                        if (flashing) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
            }
        }

        // Back navigation intentionally keeps the platform default: leaving the
        // app is always allowed (the app-scoped flash keeps running) and the
        // back key never cancels a write. Non-dismissible dialogs consume back
        // themselves while a flash is in progress.
    }

    override fun onStop() {
        super.onStop()
        // Do not leak the wake flag while the activity is not visible; it is
        // re-applied on the next STARTED emission if a flash is still running.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
