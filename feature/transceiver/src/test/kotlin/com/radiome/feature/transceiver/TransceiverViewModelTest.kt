package com.radiome.feature.transceiver

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.image.MaxEdge
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.ContentType
import com.radiome.feature.transceiver.settings.SettingsRepository
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

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = tmp.newFolder().resolve("s.preferences_pb")
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), scope)
        vm = TransceiverViewModel(client, FakeHistory(), settings, codec).apply { workDispatcher = Dispatchers.Unconfined }
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
        assertEquals(ContentType.IMAGE_WEBP, payload.type)
        assertEquals(CompressionMode.NEVER, options.compression)
        waitUntil { vm.compose.value.image == null }
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
}
