package com.radiome.feature.transceiver

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.image.MaxEdge
import com.radiome.core.protocol.image.ProgressiveImage
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.link.LinkTest
import com.radiome.core.protocol.link.LinkTestReport
import com.radiome.core.protocol.link.ProbeResult
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.ContentType
import com.radiome.feature.transceiver.settings.SettingsRepository
import com.radiome.feature.transceiver.link.PositionBeacon
import com.radiome.core.protocol.payload.Position
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TransceiverViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val client = FakeClient()
    private val codec = FakeCodec()
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var vm: TransceiverViewModel
    private val keys = FakeKeys()
    private val location = FakeLocation()
    private lateinit var beacon: PositionBeacon

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = tmp.newFolder().resolve("s.preferences_pb")
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), scope)
        beacon = PositionBeacon(client, settings, keys, location, scope)
        vm = TransceiverViewModel(client, FakeHistory(), settings, codec, FakePositions(), keys, beacon)
            .apply { workDispatcher = Dispatchers.Unconfined }
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun sendingTextNeedsTheRadioOnAndUsesTheChosenProfile() = runBlocking {
        vm.onDraftChange("hello over the air")
        assertTrue(vm.compose.value.textEstimate!!.airtimeMs > 0)

        vm.sendText()
        assertEquals("Turn the radio on first.", withTimeout(2_000) { vm.messages.first() })
        assertEquals("hello over the air", vm.compose.value.draft)

        vm.startListening()
        vm.onProfileChange(ModemProfile.ROBUST)
        vm.sendText()
        waitUntil { client.sent.size == 1 }
        val (payload, options) = client.sent.single()
        assertEquals("hello over the air", payload.asText())
        assertEquals(ModemProfile.ROBUST, options.profile)
        waitUntil { vm.compose.value.draft.isEmpty() }
        assertNull(vm.compose.value.textEstimate)
    }

    @Test
    fun pickedImageIsPreparedResizedAndSentUncompressed() = runBlocking {
        client.startListening()
        vm.onImagePicked("content://media/picked/1")
        waitUntil { vm.compose.value.image?.bytes != null }
        val draft = vm.compose.value.image!!
        assertFalse(draft.busy)
        // Default preset: 240 px on the long edge, 640x480 scaled to 240x180.
        assertEquals(240, draft.width)
        assertEquals(180, draft.height)
        assertTrue(draft.airtimeMs > 0)

        vm.onImagePresetChange(draft.preset.copy(maxEdge = MaxEdge.PX_128))
        waitUntil { vm.compose.value.image?.width == 128 }
        assertTrue(vm.compose.value.image!!.sizeBytes < draft.sizeBytes)
        waitUntil { settings.settings.value.imageMaxEdge == MaxEdge.PX_128 }

        vm.sendImage()
        waitUntil { client.sent.size == 1 }
        val (payload, options) = client.sent.single()
        // Preview first is on by default: a tiny level, then the picture.
        assertEquals(ContentType.IMAGE_PROGRESSIVE, payload.type)
        assertEquals(2, ProgressiveImage.unpack(payload.bytes)!!.size)
        assertEquals(CompressionMode.NEVER, options.compression)
        waitUntil { vm.compose.value.image == null }
    }

    @Test
    fun presetChangedWhileTheImageIsStillLoadingIsApplied() {
        val gate = CompletableDeferred<Unit>()
        codec.gate = gate
        vm.onImagePicked("content://media/picked/2")
        assertTrue(vm.compose.value.image!!.busy)

        // The decode is still running: changing the preset must not cancel it.
        vm.onImagePresetChange(vm.compose.value.image!!.preset.copy(maxEdge = MaxEdge.PX_128))
        gate.complete(Unit)

        waitUntil { vm.compose.value.image?.bytes != null }
        val draft = vm.compose.value.image!!
        assertFalse(draft.busy)
        assertEquals(128, draft.width)
    }

    @Test
    fun testToneAndMutedOutputWarnings() = runBlocking {
        vm.testTone()
        assertEquals("Turn the radio on first.", withTimeout(2_000) { vm.messages.first() })

        client.startListening()
        client.muted = true
        vm.testTone()
        assertTrue(withTimeout(2_000) { vm.messages.first() }.contains("muted"))
        waitUntil { client.testTones == 1 }

        vm.onDraftChange("hi")
        vm.sendText()
        assertTrue(withTimeout(2_000) { vm.messages.first() }.contains("muted"))
        waitUntil { client.sent.size == 1 }
    }

    @Test
    fun linkSettingsChangedWhileOnAskForARestart() {
        vm.updateSettings { it.copy(outputGainPercent = 50) }
        waitUntil { client.live?.second == 0.5f }
        assertFalse(vm.restartPending.value)

        client.startListening()
        vm.updateSettings { it.copy(leadInMs = 1000) }
        waitUntil { vm.restartPending.value }
        vm.restartLink()
        assertEquals(1, client.restarts)
        assertFalse(vm.restartPending.value)
    }

    @Test
    fun callsignAddresseeAndQuickMessagesGoIntoTheTransfer() = runBlocking {
        client.startListening()
        vm.updateSettings { it.copy(callsign = "ek1a") }
        waitUntil { client.lastCallsign == "EK1A" }
        // The settings state follows the store a moment later; sends read it.
        waitUntil { vm.settings.value.callsign == "EK1A" }
        vm.onToChange("ek2b")
        vm.sendQuickMessage("Need help")
        waitUntil { client.sent.size == 1 }
        val (payload, options) = client.sent.single()
        assertEquals("Need help", payload.asText())
        assertEquals("EK1A", options.station!!.source)
        assertEquals("EK2B", options.station!!.destination)

        vm.onToChange("")
        vm.sendQuickMessage("OK")
        waitUntil { client.sent.size == 2 }
        assertNull(client.sent[1].second.station!!.destination)
    }

    @Test
    fun heardStationsAreListedNewestFirst() {
        client.events.tryEmit(LinkEvent.StationHeard(1, "EK1A", 10.0))
        client.events.tryEmit(LinkEvent.StationHeard(2, "EK2B", 8.0))
        client.events.tryEmit(LinkEvent.StationHeard(3, "EK1A", 12.0))
        waitUntil { vm.heardStations.value.size == 2 && vm.heardStations.value.first().callsign == "EK1A" }
        assertEquals(12.0, vm.heardStations.value.first().snrDb, 0.0)
    }

    @Test
    fun linkTestShowsTheReportAndAppliesTheSuggestion() = runBlocking {
        vm.startLinkTest()
        assertEquals("Turn the radio on first.", withTimeout(2_000) { vm.messages.first() })
        client.startListening()
        vm.startLinkTest()
        waitUntil { vm.linkTest.value.running }
        val report = LinkTestReport(
            listOf(
                ProbeResult(ModemProfile.FAST, 6, 3, 3, 20, 4.0),
                ProbeResult(ModemProfile.STANDARD, 4, 4, 1, 2, 5.0),
                ProbeResult(ModemProfile.ROBUST, 2, 2, 0, 0, 5.0),
            ),
        )
        client.events.tryEmit(LinkEvent.LinkTestReported(0x4321, report, "EK2B", measuredHere = false))
        waitUntil { vm.linkTest.value.report != null }
        val ui = vm.linkTest.value
        assertFalse(ui.running)
        assertEquals("EK2B", ui.peer)
        assertEquals(ModemProfile.STANDARD, ui.recommendation!!.profile)

        vm.applyLinkTestRecommendation()
        waitUntil { settings.settings.value.defaultProfile == ModemProfile.STANDARD && settings.settings.value.parityPercent == 20 }
        assertEquals(ModemProfile.STANDARD, vm.compose.value.profile)
        vm.dismissLinkTest()
        assertFalse(vm.linkTest.value.visible)

        vm.startLinkTest()
        waitUntil { vm.linkTest.value.running }
        client.events.tryEmit(LinkEvent.LinkTestFailed(0x4321, "no report"))
        waitUntil { vm.linkTest.value.error != null }
        assertEquals(2, client.linkTests)
        assertTrue(LinkTest.order.isNotEmpty())
    }

    @Test
    fun positionsAreSentOnlyEncryptedAndOnlyWithAKey() = runBlocking {
        client.startListening()
        vm.sendPositionNow()
        assertTrue(withTimeout(2_000) { vm.messages.first() }.startsWith("Set a group key"))
        assertTrue(client.sent.isEmpty())

        vm.setGroupPassphrase("short")
        assertTrue(withTimeout(2_000) { vm.messages.first() }.contains("at least"))
        vm.setGroupPassphrase("our group passphrase")
        assertTrue(withTimeout(5_000) { vm.messages.first() }.contains("Fingerprint"))
        assertEquals(keys.key.value, client.lastGroupKey)

        location.permitted = false
        vm.sendPositionNow()
        assertTrue(withTimeout(2_000) { vm.messages.first() }.contains("location access"))
        location.permitted = true
        vm.sendPositionNow()
        assertTrue(withTimeout(2_000) { vm.messages.first() }.startsWith("Encrypted position sent"))
        val (payload, options) = client.sent.single()
        assertEquals(ContentType.POSITION, payload.type)
        assertTrue(options.encrypt)
        assertEquals(64, options.chunkPayloadSize)
        assertEquals(40.1776, Position.decode(payload.bytes)!!.latitude, 1e-7)

        vm.clearGroupKey()
        withTimeout(2_000) { vm.messages.first() }
        assertNull(client.lastGroupKey)
    }

    @Test
    fun encryptMessagesNeedsAKey() = runBlocking {
        client.startListening()
        vm.updateSettings { it.copy(encryptMessages = true) }
        waitUntil { settings.settings.value.encryptMessages }
        vm.onDraftChange("secret plan")
        vm.sendText()
        assertTrue(withTimeout(2_000) { vm.messages.first() }.contains("no group key"))
        assertTrue(client.sent.isEmpty())
        keys.setPassphrase("our group passphrase")
        vm.sendText()
        waitUntil { client.sent.size == 1 }
        assertTrue(client.sent.single().second.encrypt)
    }

    @Test
    fun queuedMessagesGoOutOneAfterAnother() = runBlocking {
        vm.onDraftChange("first")
        vm.queueText()
        vm.onDraftChange("second")
        vm.queueText()
        assertEquals(2, vm.queue.value.size)
        assertTrue(client.sent.isEmpty())

        vm.startListening()
        waitUntil { client.sent.size == 1 }
        assertEquals("first", client.sent[0].first.asText())
        Thread.sleep(200)
        assertEquals(1, client.sent.size)

        client.events.tryEmit(LinkEvent.Delivered(client.nextSid - 1, by = "N0CALL"))
        waitUntil { client.sent.size == 2 }
        assertEquals("second", client.sent[1].first.asText())
        waitUntil { vm.queue.value.isEmpty() }
    }

    @Test
    fun autoSpeedStepsDownAfterAFailedTransfer() = runBlocking {
        vm.startListening()
        vm.onProfileChange(ModemProfile.STANDARD)
        vm.onAutoSpeed(true)
        val start = vm.compose.value.profile
        vm.onDraftChange("will not make it")
        vm.sendText()
        waitUntil { client.sent.size == 1 }
        client.events.tryEmit(LinkEvent.Failed(client.nextSid - 1, "no answer"))
        waitUntil { vm.compose.value.profile != start }
        assertEquals(ModemProfile.ROBUST, vm.compose.value.profile)
        assertTrue(vm.compose.value.auto)
    }

    @Test
    fun queuedEncryptedMessageIsDroppedWhenTheKeyIsGone() = runBlocking {
        settings.update { it.copy(encryptMessages = true) }
        waitUntil { vm.settings.value.encryptMessages }
        keys.setPassphrase("correct horse battery")
        vm.onDraftChange("sealed")
        vm.queueText()
        vm.onDraftChange("plain")
        assertEquals(1, vm.queue.value.size)
        settings.update { it.copy(encryptMessages = false) }
        waitUntil { !vm.settings.value.encryptMessages }
        vm.queueText()
        assertTrue(vm.queue.value[0].options.encrypt)
        assertFalse(vm.queue.value[1].options.encrypt)
        keys.clear()

        vm.startListening()
        waitUntil { client.sent.size == 1 }
        assertEquals("plain", client.sent.single().first.asText())
        assertTrue(vm.queue.value.isEmpty())
    }

    @Test
    fun queueMovesOnWhenTheRadioStopsMidTransfer() = runBlocking {
        vm.onDraftChange("first")
        vm.queueText()
        vm.onDraftChange("second")
        vm.queueText()
        vm.startListening()
        waitUntil { client.sent.size == 1 }
        Thread.sleep(200) // the queue is now waiting for that transfer to finish

        vm.stopListening()
        vm.startListening()
        waitUntil { client.sent.size == 2 }
        assertEquals("second", client.sent[1].first.asText())
    }

    @Test
    fun pickedFileIsSentAsBinaryWithItsName() = runBlocking {
        vm.attachFile("Expedition to Aragats summit day two.gpx", ByteArray(2_000) { (it % 7).toByte() })
        val draft = vm.compose.value.file!!
        assertTrue(draft.name.endsWith(".gpx"))
        assertTrue(draft.estimate.airtimeMs > 0)

        vm.attachFile("huge.bin", ByteArray(com.radiome.core.protocol.payload.Files.MAX_BYTES + 1))
        assertTrue(withTimeout(2_000) { vm.messages.first() }.contains("KB can be sent"))
        assertEquals(draft, vm.compose.value.file)

        vm.startListening()
        vm.sendFile()
        waitUntil { client.sent.size == 1 }
        val (payload, _) = client.sent.single()
        assertEquals(ContentType.BINARY, payload.type)
        assertEquals(draft.name, payload.name)
        assertEquals(2_000, payload.bytes.size)
        waitUntil { vm.compose.value.file == null }
    }
}
