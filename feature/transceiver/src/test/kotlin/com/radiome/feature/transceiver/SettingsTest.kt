package com.radiome.feature.transceiver

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.radiome.core.audio.RoutePreference
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.image.MaxEdge
import com.radiome.feature.transceiver.settings.RadioSettings
import com.radiome.feature.transceiver.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun outOfRangeValuesAreClampedAndMapped() {
        val s = RadioSettings(
            leadInMs = 5000, parityPercent = 80, interleaveDepth = 40, chunkPayloadSize = 10,
            turnaroundMs = 50, outputGainPercent = 0, imageQuality = 99,
        ).sanitized()
        assertEquals(1500, s.leadInMs)
        assertEquals(50, s.parityPercent)
        assertEquals(16, s.interleaveDepth)
        assertEquals(32, s.chunkPayloadSize)
        assertEquals(500, s.turnaroundMs)
        assertEquals(10, s.outputGainPercent)
        assertEquals(40, s.imageQuality)

        // Mapping never throws, even from unsanitised values.
        val raw = RadioSettings(leadInMs = 50, parityPercent = 5)
        assertEquals(200, raw.modemConfig().leadInMs)
        assertEquals(0.20, raw.transferOptions().parityRatio!!, 1e-9)
    }

    @Test
    fun transferOptionsFollowProfileAndDefaults() {
        val s = RadioSettings(chunkPayloadSize = 40)
        val o = s.transferOptions(ModemProfile.ROBUST)
        assertEquals(ModemProfile.ROBUST, o.profile)
        assertEquals(40, o.chunkPayloadSize)
        assertNull(o.parityRatio)
        assertEquals(ModemProfile.ROBUST.defaultParityRatio, o.effectiveParityRatio, 1e-9)
    }

    @Test
    fun onlyLinkSettingsNeedARestart() {
        val base = RadioSettings()
        assertTrue(base.copy(leadInMs = 900).needsLinkRestart(base))
        assertTrue(base.copy(autoNack = false).needsLinkRestart(base))
        assertFalse(base.copy(defaultProfile = ModemProfile.FAST).needsLinkRestart(base))
        assertFalse(base.copy(outputGainPercent = 50, routePreference = RoutePreference.CABLE).needsLinkRestart(base))
    }

    @Test
    fun dataStoreRoundTrip() = runBlocking {
        val file = tmp.newFolder().resolve("s.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), scope)
        assertEquals(RadioSettings(), repo.load())

        val saved = repo.update {
            it.copy(
                leadInMs = 950, parityPercent = 40, defaultProfile = ModemProfile.ROBUST, imageMaxEdge = MaxEdge.PX_128, autoNack = false,
                callsign = "ek1a/p", quickMessages = listOf("Copy", " ", "At the hut"), imageProgressive = false,
            )
        }
        assertEquals("EK1A/P", saved.callsign)
        assertEquals(listOf("Copy", "At the hut"), saved.quickMessages)
        assertEquals(950, saved.leadInMs)
        val loaded = repo.load()
        assertEquals(saved, loaded)
        waitUntil { repo.settings.value == saved }
        assertEquals(saved, repo.current())

        // Back to "profile default" survives the round trip as null, not 0.
        assertNull(repo.update { it.copy(parityPercent = null) }.parityPercent)
        assertNull(repo.load().parityPercent)
        scope.cancel()
    }

    @Test
    fun callsignAndAddresseeMakeTheStationFrame() {
        // To everyone: a broadcast (merged corrections), unless group mode is off.
        assertTrue(RadioSettings().transferOptions().station!!.broadcast)
        assertNull(RadioSettings(groupMode = false).transferOptions().station)
        assertFalse(RadioSettings(callsign = "EK1A").transferOptions(to = "EK2B").station!!.broadcast)
        val o = RadioSettings(callsign = "ek1a").transferOptions(ModemProfile.FAST, to = "ek2b")
        assertEquals("EK1A", o.station!!.source)
        assertEquals("EK2B", o.station!!.destination)
        assertNull(RadioSettings(callsign = "EK1A").transferOptions(to = " ").station!!.destination)
        assertTrue(RadioSettings().imagePreset().progressive)
    }
}
