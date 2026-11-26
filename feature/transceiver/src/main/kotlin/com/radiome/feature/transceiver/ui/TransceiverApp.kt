package com.radiome.feature.transceiver.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radiome.feature.transceiver.TransceiverViewModel

private enum class Tab(val label: String, val icon: ImageVector) {
    RADIO("Radio", Icons.Filled.Home),
    MAP("Map", Icons.Filled.LocationOn),
    HISTORY("History", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Whole app UI: radio dashboard and composer, history, settings. */
@Composable
fun TransceiverApp(viewModel: TransceiverViewModel = hiltViewModel()) {
    val radio by viewModel.radio.collectAsStateWithLifecycle()
    val compose by viewModel.compose.collectAsStateWithLifecycle()
    val history by viewModel.historyItems.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val restartPending by viewModel.restartPending.collectAsStateWithLifecycle()
    val linkTest by viewModel.linkTest.collectAsStateWithLifecycle()
    val heard by viewModel.heardStations.collectAsStateWithLifecycle()
    val positions by viewModel.positions.collectAsStateWithLifecycle()
    val groupKey by viewModel.groupKey.collectAsStateWithLifecycle()
    val keyBusy by viewModel.keyBusy.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.RADIO) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    // The request may hold only POST_NOTIFICATIONS, so check the microphone grant itself. Notifications are optional.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startListening()
        }
    }
    val startListening = {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wanted = buildList {
            if (!mic) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (mic && wanted.isEmpty()) {
            viewModel.startListening()
        } else {
            PermissionPrompts.markAsked(context, wanted)
            permissions.launch(wanted.toTypedArray())
        }
    }
    // Location is asked for only when the user wants to send a position; the map itself needs no permission.
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) viewModel.onLocationPermissionGranted()
    }
    val sendPosition = {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            viewModel.sendPositionNow()
        } else {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.onImagePicked(it.toString()) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.RADIO -> RadioScreen(
                radio = radio,
                compose = compose,
                restartPending = restartPending,
                onStart = startListening,
                onStop = viewModel::stopListening,
                onRestart = viewModel::restartLink,
                onTestTone = { viewModel.testTone() },
                onDraftChange = viewModel::onDraftChange,
                onProfileChange = viewModel::onProfileChange,
                onSendText = viewModel::sendText,
                onPickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onImagePreset = viewModel::onImagePresetChange,
                onClearImage = viewModel::clearImage,
                onSendImage = viewModel::sendImage,
                onRequestCorrections = { viewModel.requestCorrections(it) },
                onCancel = { viewModel.cancel(it) },
                linkTest = linkTest,
                heard = heard,
                quickMessages = settings.quickMessages,
                onLinkTest = { viewModel.startLinkTest() },
                onApplyLinkTest = viewModel::applyLinkTestRecommendation,
                onDismissLinkTest = viewModel::dismissLinkTest,
                onToChange = viewModel::onToChange,
                onQuickMessage = viewModel::sendQuickMessage,
                modifier = modifier,
            )
            Tab.MAP -> MapScreen(
                positions = positions,
                hasKey = groupKey != null,
                fingerprint = groupKey?.fingerprint,
                now = now,
                onSendPosition = { sendPosition() },
                onClear = { viewModel.clearPositions() },
                modifier = modifier,
            )
            Tab.HISTORY -> HistoryScreen(history, { viewModel.deleteHistory(it) }, modifier)
            Tab.SETTINGS -> SettingsScreen(
                settings = settings,
                onChange = viewModel::updateSettings,
                onBeforeRevokeMicrophone = viewModel::stopListening,
                groupKeyFingerprint = groupKey?.fingerprint,
                keyBusy = keyBusy,
                onSetPassphrase = viewModel::setGroupPassphrase,
                onClearKey = { viewModel.clearGroupKey() },
                modifier = modifier,
            )
        }
    }
}
