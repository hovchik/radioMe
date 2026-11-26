package com.radiome.feature.transceiver.ui

import com.radiome.feature.transceiver.i18n.tr
import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radiome.core.protocol.payload.Files
import com.radiome.feature.transceiver.TransceiverViewModel
import com.radiome.feature.transceiver.share.HistoryExport
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    RADIO("Radio", Icons.Filled.Home),
    MAP("Map", Icons.Filled.LocationOn),
    HISTORY("History", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Whole app UI: radio dashboard and composer, history, settings. */
@Composable
fun TransceiverApp(
    viewModel: TransceiverViewModel = hiltViewModel(),
    /** The widget or quick-settings tile asked to turn the radio on. */
    startRequested: Boolean = false,
    onStartRequestHandled: () -> Unit = {},
) {
    val radio by viewModel.radio.collectAsStateWithLifecycle()
    val compose by viewModel.compose.collectAsStateWithLifecycle()
    val history by viewModel.historyItems.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val restartPending by viewModel.restartPending.collectAsStateWithLifecycle()
    val linkTest by viewModel.linkTest.collectAsStateWithLifecycle()
    val heard by viewModel.heardStations.collectAsStateWithLifecycle()
    val positions by viewModel.positions.collectAsStateWithLifecycle()
    val trails by viewModel.trails.collectAsStateWithLifecycle()
    val groupKey by viewModel.groupKey.collectAsStateWithLifecycle()
    val keyBusy by viewModel.keyBusy.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val pttDevice by viewModel.pttDevice.collectAsStateWithLifecycle()
    val queueActions = remember(viewModel) {
        QueueActions(viewModel::onAutoSpeed, viewModel::queueText, viewModel::queueImage, viewModel::removeQueued)
    }
    var tab by rememberSaveable { mutableStateOf(Tab.RADIO) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(viewModel) {
        viewModel.buzz.collect { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
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
    LaunchedEffect(startRequested) {
        if (startRequested) {
            onStartRequestHandled()
            if (!viewModel.radio.value.running) startListening()
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
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val picked = FileIo.read(context, uri, Files.MAX_BYTES)
            when {
                picked == null -> viewModel.notify(tr("That file could not be read."))
                picked.bytes == null -> viewModel.onFileTooLarge(picked.size)
                else -> viewModel.attachFile(picked.name, picked.bytes)
            }
        }
    }
    val micForVoice = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoiceRecording() else viewModel.notify(tr("Allow the microphone to record a voice note."))
    }
    val voiceActions = remember(viewModel) {
        VoiceActions(
            onRecord = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startVoiceRecording()
                } else {
                    micForVoice.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStop = viewModel::stopVoiceRecording,
            onPlay = viewModel::playVoiceDraft,
            onClear = viewModel::clearVoice,
            onSend = viewModel::sendVoice,
            onQueue = viewModel::queueVoice,
        )
    }
    val fileActions = remember(viewModel) {
        FileActions({ filePicker.launch(arrayOf("*/*")) }, viewModel::clearFile, viewModel::sendFile, viewModel::queueFile)
    }
    // Saving: the document picker asks where, then we write what is waiting here.
    var pendingSave by remember { mutableStateOf<ByteArray?>(null) }
    val saveContract = remember {
        object : ActivityResultContracts.CreateDocument("*/*") {
            override fun createIntent(context: Context, input: String): Intent =
                super.createIntent(context, input).setType(Files.mimeType(input))
        }
    }
    val saver = rememberLauncherForActivityResult(saveContract) { uri ->
        val bytes = pendingSave
        pendingSave = null
        if (uri != null && bytes != null) scope.launch {
            viewModel.notify(if (FileIo.write(context, uri, bytes)) tr("Saved.") else tr("Could not save the file."))
        }
    }
    val historyActions = HistoryActions(
        onDelete = { viewModel.deleteHistory(it) },
        onPlayVoice = { r -> r.payload?.let(viewModel::playVoice) },
        onShareLog = { FileIo.shareText(context, tr("RadioMe history"), viewModel.historyText()) },
        onSaveLog = {
            pendingSave = viewModel.historyText().toByteArray(Charsets.UTF_8)
            saver.launch("radiome-history.txt")
        },
        onSave = { r ->
            r.payload?.let { bytes ->
                pendingSave = displayImageBytes(r.contentType, bytes)
                saver.launch(HistoryExport.suggestedName(r))
            }
        },
        onSaveToGallery = { r ->
            r.payload?.let { bytes ->
                scope.launch {
                    val ok = FileIo.saveToGallery(context, HistoryExport.suggestedName(r), "image/webp", displayImageBytes(r.contentType, bytes))
                    viewModel.notify(if (ok) tr("Saved to Pictures/RadioMe.") else tr("Could not save the picture."))
                }
            }
        },
    )

    LargeText(settings.largeText) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(tr(t.label)) },
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
                    onProfileChange = { viewModel.onProfileChange(it) },
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
                    queue = queue,
                    queueActions = queueActions,
                    fileActions = fileActions,
                    voiceActions = voiceActions,
                    waterfall = if (settings.showWaterfall) viewModel.waterfall else null,
                    modifier = modifier,
                )
                Tab.MAP -> MapScreen(
                    positions = positions,
                    trails = trails,
                    hasKey = groupKey != null,
                    fingerprint = groupKey?.fingerprint,
                    now = now,
                    onSendPosition = { sendPosition() },
                    onMessage = { callsign ->
                        viewModel.onToChange(callsign)
                        tab = Tab.RADIO
                    },
                    onClear = { viewModel.clearPositions() },
                    modifier = modifier,
                )
                Tab.HISTORY -> HistoryScreen(history, historyActions, modifier)
                Tab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onChange = viewModel::updateSettings,
                    onBeforeRevokeMicrophone = viewModel::stopListening,
                    groupKeyFingerprint = groupKey?.fingerprint,
                    keyBusy = keyBusy,
                    onSetPassphrase = viewModel::setGroupPassphrase,
                    onClearKey = { viewModel.clearGroupKey() },
                    pttDevice = pttDevice,
                    modifier = modifier,
                )
            }
        }
    }
}

/** "Larger text" setting: scales fonts by 1.3 on top of the system font size. */
@Composable
private fun LargeText(on: Boolean, content: @Composable () -> Unit) {
    if (!on) return content()
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * 1.3f), content = content)
}
