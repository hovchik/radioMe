package com.radiome.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.radiome.feature.transceiver.ui.RadioMeTheme
import com.radiome.feature.transceiver.ui.TransceiverApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Set when the widget or quick-settings tile asked to turn the radio on. */
    private val startRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeStartRequest(intent)
        setContent {
            RadioMeTheme {
                TransceiverApp(startRequested = startRequested.value, onStartRequestHandled = { startRequested.value = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeStartRequest(intent)
    }

    private fun takeStartRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(RadioControls.EXTRA_START, false) == true) {
            startRequested.value = true
            intent.removeExtra(RadioControls.EXTRA_START)
        }
    }
}
