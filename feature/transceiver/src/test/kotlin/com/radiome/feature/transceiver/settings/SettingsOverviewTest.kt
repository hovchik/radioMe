package com.radiome.feature.transceiver.settings

import com.radiome.core.audio.PttMode
import com.radiome.core.audio.RouteMode
import com.radiome.feature.transceiver.link.RouteInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOverviewTest {
    private val allowed = permissionRows(34, micGranted = true, notificationsEnabled = true, askedBefore = emptySet(), showRationale = emptySet(), locationGranted = true, preciseLocation = true)
    private val cable = RouteInfo(RouteMode.CABLE, "USB audio", "USB audio")

    private fun item(items: List<OverviewItem>, s: SettingsSection) = items.single { it.section == s }

    @Test
    fun everythingSetUpIsReady() {
        val items = settingsOverview(RadioSettings(callsign = "EK1ABC"), "AB12", SettingsRadioStatus(running = true, route = cable), allowed)
        assertEquals(Health.OK, overallHealth(items))
        assertEquals(0, needsAttention(items))
        assertEquals(listOf("EK1ABC"), item(items, SettingsSection.STATION).args)
        assertEquals(listOf("USB audio"), item(items, SettingsSection.CONNECTION).args)
    }

    @Test
    fun freshInstallPointsAtWhatIsMissing() {
        val denied = permissionRows(34, micGranted = false, notificationsEnabled = false, askedBefore = emptySet(), showRationale = emptySet())
        val items = settingsOverview(RadioSettings(), null, SettingsRadioStatus(), denied)
        assertEquals(Health.ATTENTION, item(items, SettingsSection.STATION).health)
        assertEquals(Health.IDLE, item(items, SettingsSection.CONNECTION).health)
        assertEquals(Health.ATTENTION, item(items, SettingsSection.SECURITY).health)
        // Without the microphone the radio can't listen at all.
        assertEquals(Health.PROBLEM, item(items, SettingsSection.PERMISSIONS).health)
        assertEquals(Health.PROBLEM, overallHealth(items))
        assertEquals(3, needsAttention(items))
    }

    @Test
    fun connectionProblemsShowWhileTheRadioRuns() {
        fun health(radio: SettingsRadioStatus, s: RadioSettings = RadioSettings()) =
            item(settingsOverview(s, "AB12", radio, allowed), SettingsSection.CONNECTION).health

        assertEquals(Health.ATTENTION, health(SettingsRadioStatus(running = true, route = cable.copy(cableMissing = true))))
        assertEquals(Health.PROBLEM, health(SettingsRadioStatus(running = true, route = cable.copy(pttMissing = true))))
        // Serial PTT picked but no adapter: transmissions would never key the radio.
        assertEquals(Health.PROBLEM, health(SettingsRadioStatus(running = true, route = cable), RadioSettings(pttMode = PttMode.SERIAL_RTS)))
        assertEquals(Health.OK, health(SettingsRadioStatus(running = true, route = cable, pttDevice = "CP2102"), RadioSettings(pttMode = PttMode.SERIAL_RTS)))
        assertEquals(Health.IDLE, health(SettingsRadioStatus(running = false, route = cable.copy(pttMissing = true))))
    }

    @Test
    fun automaticBeaconWithoutKeyIsAProblem() {
        val items = settingsOverview(RadioSettings(callsign = "A1", beaconIntervalMin = 15), null, SettingsRadioStatus(), allowed)
        assertEquals(Health.PROBLEM, item(items, SettingsSection.SECURITY).health)
    }

    @Test
    fun restoringDefaultsKeepsIdentity() {
        val custom = RadioSettings(callsign = "EK1ABC", quickMessages = listOf("Hi"), leadInMs = 1200, parityPercent = 40, haptics = false)
        val restored = custom.restoredDefaults()
        assertEquals("EK1ABC", restored.callsign)
        assertEquals(listOf("Hi"), restored.quickMessages)
        assertEquals(RadioSettings().leadInMs, restored.leadInMs)
        assertEquals(null, restored.parityPercent)
        assertTrue(restored.haptics)
    }

    @Test
    fun advancedDefaultsTouchOnlyAdvancedSettings() {
        val custom = RadioSettings(parityPercent = 40, turnaroundMs = 3000, leadInMs = 1200)
        assertTrue(custom.advancedChanged)
        val reset = custom.advancedDefaults()
        assertFalse(reset.advancedChanged)
        assertEquals(1200, reset.leadInMs)
        assertFalse(RadioSettings().advancedChanged)
    }
}
