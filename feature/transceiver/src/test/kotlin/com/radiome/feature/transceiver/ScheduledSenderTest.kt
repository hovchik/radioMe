package com.radiome.feature.transceiver

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.payload.Position
import com.radiome.feature.transceiver.link.FileScheduleStore
import com.radiome.feature.transceiver.link.InMemoryScheduleStore
import com.radiome.feature.transceiver.link.PositionBeacon
import com.radiome.feature.transceiver.link.Repeat
import com.radiome.feature.transceiver.link.ScheduledMessage
import com.radiome.feature.transceiver.link.ScheduledSender
import com.radiome.feature.transceiver.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScheduledSenderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val client = FakeClient()
    private val keys = FakeKeys()
    private val location = FakeLocation()
    private val store = InMemoryScheduleStore()
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var sender: ScheduledSender

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = tmp.newFolder().resolve("s.preferences_pb")
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), scope)
        val beacon = PositionBeacon(client, settings, keys, location, scope)
        sender = ScheduledSender(client, settings, keys, beacon, store, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun waitsForItsTimeAndForTheRadio() {
        sender.start()
        sender.add("hi", Payload.text("hi"), "ek2b", ModemProfile.ROBUST, System.currentTimeMillis() + 200)
        Thread.sleep(400)
        assertTrue(client.sent.isEmpty()) // radio off
        assertEquals(1, sender.items.value.size)

        client.startListening()
        waitUntil { client.sent.size == 1 }
        val (payload, options) = client.sent.single()
        assertEquals("hi", payload.asText())
        assertEquals(ModemProfile.ROBUST, options.profile)
        assertEquals("EK2B", options.station?.destination)
        waitUntil { sender.items.value.isEmpty() }
        waitUntil { store.items.isEmpty() }
    }

    @Test
    fun repeatingMessageStaysForItsNextTime() {
        client.startListening()
        sender.start()
        val m = sender.add("ping", Payload.text("ping"), "", null, System.currentTimeMillis() + 50, Repeat.HOURLY)
        waitUntil { client.sent.size == 1 }
        waitUntil { sender.items.value.singleOrNull()?.dueAt?.let { it > m.dueAt } == true }
        assertEquals(m.dueAt + Repeat.HOURLY.periodMs, sender.items.value.single().dueAt)
        Thread.sleep(200)
        assertEquals(1, client.sent.size)
    }

    @Test
    fun sendNowAndCancel() {
        client.startListening()
        sender.start()
        val later = sender.add("later", Payload.text("later"), "", null, System.currentTimeMillis() + 3_600_000)
        val gone = sender.add("gone", Payload.text("gone"), "", null, System.currentTimeMillis() + 3_600_000)
        sender.remove(gone.id)
        sender.sendNow(later.id)
        waitUntil { client.sent.size == 1 }
        assertEquals("later", client.sent.single().first.asText())
        waitUntil { sender.items.value.isEmpty() }
    }

    @Test
    fun scheduledPositionReadsTheFixWhenDue() = runBlocking {
        keys.setPassphrase("correct horse battery")
        client.startListening()
        sender.start()
        location.fix = Position(40.0, 44.0, accuracyM = 5, timeSec = 1_795_000_000)
        sender.add("My position", null, "", null, System.currentTimeMillis() + 50)
        waitUntil { client.sent.size == 1 }
        assertEquals(ContentType.POSITION, client.sent.single().first.type)
        assertTrue(client.sent.single().second.encrypt)
    }

    @Test
    fun encryptedScheduleIsDroppedWithoutAKey() = runBlocking {
        settings.update { it.copy(encryptMessages = true) }
        client.startListening()
        sender.start()
        sender.add("secret", Payload.text("secret"), "", null, System.currentTimeMillis() + 50)
        waitUntil { sender.items.value.isEmpty() }
        assertTrue(client.sent.isEmpty())
    }

    @Test
    fun savedListSurvivesARestart() = runBlocking {
        val file = tmp.newFolder().resolve("scheduled.bin")
        val items = listOf(
            ScheduledMessage(7, "pic", Payload(ContentType.IMAGE_WEBP, byteArrayOf(1, 2, 3), "a.webp"), "EK2B", ModemProfile.FAST, 1234, Repeat.DAILY),
            ScheduledMessage(8, "My position", null, "", null, 5678),
        )
        FileScheduleStore(file).save(items)
        val back = FileScheduleStore(file).load()
        assertEquals(2, back.size)
        assertEquals(items[0].copy(payload = null), back[0].copy(payload = null))
        assertArrayEquals(byteArrayOf(1, 2, 3), back[0].payload!!.bytes)
        assertEquals("a.webp", back[0].payload!!.name)
        assertNull(back[1].payload)
        assertEquals(items[1], back[1])
    }
}
