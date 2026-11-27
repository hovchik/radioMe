package com.radiome.feature.transceiver.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import com.radiome.core.audio.RouteMode
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.LinkState
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.data.TransferRecord
import com.radiome.data.TransferStatus
import com.radiome.feature.transceiver.ChatThread
import com.radiome.feature.transceiver.ComposeUi
import com.radiome.feature.transceiver.HeardStation
import com.radiome.feature.transceiver.InputLevel
import com.radiome.feature.transceiver.LinkTestUi
import com.radiome.feature.transceiver.QueuedItem
import com.radiome.feature.transceiver.RadioUi
import com.radiome.feature.transceiver.TransferCard
import com.radiome.feature.transceiver.link.RouteInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Radio, Chat and History tabs in their redesigned form, in the states people meet most, so a layout
 * crash shows up here rather than on a phone. Screenshots go to build/outputs/screenshots for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreensRedesignTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L

    private val noop: () -> Unit = {}
    private val queueActions = QueueActions({}, {}, {}, {}, onSchedule = { _, _ -> })
    private val fileActions = FileActions({}, {}, {}, {})
    private val voiceActions = VoiceActions({}, {}, {}, {}, {}, {})
    private val historyActions = HistoryActions(onDelete = {}, onPlayVoice = {}, onShareLog = {}, onSaveLog = {}, onSave = {}, onSaveToGallery = {})
    private val chatActions = ChatActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun record(
        id: Long,
        direction: Direction,
        status: TransferStatus,
        text: String,
        peer: String? = "K1ABC",
        at: Long = now - id * 60_000,
    ) = TransferRecord(
        id = id, sessionId = 0x1000 + id.toInt(), direction = direction, status = status, contentType = ContentType.TEXT,
        name = "", text = text, payload = text.toByteArray(), totalChunks = 2, receivedChunks = 2, fecCorrectedBytes = 0,
        lossPercent = 0.0, repairRounds = 0, profile = "STANDARD", createdAt = at, updatedAt = at, peer = peer,
    )

    private val records = listOf(
        record(1, Direction.INCOMING, TransferStatus.COMPLETE, "Arrived at the ridge, all fine."),
        record(2, Direction.OUTGOING, TransferStatus.DELIVERED, "Copy that, moving on.").copy(deliveredTo = listOf("K1ABC")),
        record(3, Direction.OUTGOING, TransferStatus.FAILED, "Need water at camp 2"),
    )

    private fun transfer() = TransferCard(
        sessionId = 0x2A, direction = Direction.INCOMING, phase = "RECEIVING", name = "", totalChunks = 12, receivedChunks = 7,
        fecRepairedChunks = 1, fecCorrectedBytes = 4, missing = emptyList(), lossPercent = 0.0, repairRounds = 0, snrDb = 11.2,
        chunkStates = emptyList(), elapsedMs = 4_000, startedAtMs = now - 4_000, updatedAtMs = now, canRequestCorrections = false,
        canCancel = false, finished = false, peer = "K1ABC",
    )

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            RadioMeTheme(darkTheme = dark, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        val dir = File(System.getProperty("user.dir"), "build/outputs/screenshots").apply { mkdirs() }
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { File(dir, "$name.error.txt").writeText(it.stackTraceToString()) }
    }

    @Composable
    private fun Radio(radio: RadioUi, compose: ComposeUi = ComposeUi(), queue: List<QueuedItem> = emptyList(), onStart: () -> Unit = noop) {
        RadioScreen(
            radio = radio, compose = compose, restartPending = radio.running, onStart = onStart, onStop = noop, onRestart = noop,
            onTestTone = noop, onDraftChange = {}, onProfileChange = {}, onSendText = noop, onPickImage = noop, onImagePreset = {},
            onClearImage = noop, onSendImage = noop, onRequestCorrections = {}, onCancel = {}, linkTest = LinkTestUi(),
            heard = listOf(HeardStation("K1ABC", now - 60_000, 9.0)), quickMessages = listOf("Copy that", "On my way"),
            onLinkTest = noop, onApplyLinkTest = noop, onDismissLinkTest = noop, onToChange = {}, onQuickMessage = {}, queue = queue,
            queueActions = queueActions, fileActions = fileActions, voiceActions = voiceActions, waterfall = null,
        )
    }

    @Test
    fun radioOffSaysHowToStart() {
        var started = 0
        show { Radio(RadioUi(running = false), onStart = { started++ }) }
        compose.onNodeWithText("Start listening to receive. Sending also needs the radio on.").assertExists()
        snap("radio_1_off")
        compose.onNodeWithText("Start listening").performClick()
        compose.waitForIdle()
        assertEquals(1, started)
    }

    @Test
    fun radioRunningShowsLevelsTransferAndQueue() {
        val radio = RadioUi(
            running = true, linkState = LinkState.LISTENING, route = RouteInfo(RouteMode.CABLE, "USB audio", "USB audio"),
            levelDbfs = -22.0, inputLevel = InputLevel.GOOD, snrDb = 12.5, transfers = listOf(transfer()),
        )
        val queue = listOf(QueuedItem(7, "Need water at camp 2", Payload.text("Need water at camp 2"), TransferOptions(), autoProfile = true))
        show { Radio(radio, ComposeUi(to = "K1ABC", draft = "On the way"), queue) }
        compose.onNodeWithText("Listening").assertExists()
        compose.onNodeWithText("On the air now").assertExists()
        snap("radio_2_running")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Queue (1)"))
        snap("radio_3_send_and_queue")
    }

    @Test
    fun radioDarkTheme() {
        show(dark = true) { Radio(RadioUi(running = true, levelDbfs = -30.0, inputLevel = InputLevel.GOOD)) }
        snap("radio_4_dark")
    }

    @Test
    fun historyShowsSummaryFailedAndDetails() {
        show { HistoryScreen(items = records, actions = historyActions, running = true, now = now) }
        compose.onAllNodesWithText("1 messages not delivered").onFirst().assertExists()
        snap("history_1_top")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Need water at camp 2"))
        compose.onAllNodesWithText("Details").onFirst().performClick()
        compose.waitForIdle()
        snap("history_2_details")
    }

    @Test
    fun historyEmpty() {
        show { HistoryScreen(items = emptyList(), actions = historyActions, now = now) }
        compose.onNodeWithText("Messages, pictures and files you send or receive are kept here.").assertExists()
        snap("history_3_empty")
    }

    @Test
    fun chatListAndConversation() {
        val threads = listOf(
            ChatThread(null, records[1], 0),
            ChatThread("K1ABC", records[0], 2, lastHeardMs = now - 120_000),
        )
        show {
            ChatScreen(
                threads = threads, records = records, transfers = emptyList(), queue = emptyList(), compose = ComposeUi(),
                running = false, quickMessages = listOf("Copy that"), now = now, actions = chatActions, queueActions = queueActions,
                fileActions = fileActions, voiceActions = voiceActions, historyActions = historyActions,
            )
        }
        compose.onNodeWithText("Conversations · 2 unread").assertExists()
        snap("chat_1_list")
        compose.onNodeWithText("K1ABC").performClick()
        compose.waitForIdle()
        snap("chat_2_conversation")
    }
}
