package com.radiome.feature.transceiver.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.radiome.core.audio.RouteMode
import com.radiome.feature.transceiver.link.RouteInfo
import com.radiome.feature.transceiver.settings.RadioSettings
import com.radiome.feature.transceiver.settings.SettingsRadioStatus
import com.radiome.feature.transceiver.settings.permissionRows
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Settings screen in the states people meet most (fresh install, fully set up) and drives its main
 * controls. Also writes screenshots to build/outputs/screenshots, which CI uploads for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val freshPermissions = permissionRows(34, micGranted = false, notificationsEnabled = false, askedBefore = emptySet(), showRationale = emptySet())
    private val allowedPermissions = permissionRows(
        34, micGranted = true, notificationsEnabled = true, askedBefore = emptySet(), showRationale = emptySet(),
        locationGranted = true, preciseLocation = true,
    )

    private var settings by mutableStateOf(RadioSettings())

    private fun show(
        fingerprint: String?,
        radio: SettingsRadioStatus,
        permissions: Boolean,
        dark: Boolean = false,
    ) {
        compose.setContent {
            RadioMeTheme(darkTheme = dark, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        settings = settings,
                        onChange = { change -> settings = change(settings).sanitized() },
                        onBeforeRevokeMicrophone = {},
                        groupKeyFingerprint = fingerprint,
                        keyBusy = false,
                        onSetPassphrase = {},
                        onClearKey = {},
                        radio = radio,
                        permissionRowsOverride = if (permissions) allowedPermissions else freshPermissions,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        // Screenshots are for review only: a capture failure must not hide what the test checks.
        runCatching {
            val dir = File(System.getProperty("user.dir"), "build/outputs/screenshots").apply { mkdirs() }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("Screenshot $name not saved: $it") }
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithText(text).performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun freshInstallShowsWhatIsMissing() {
        show(fingerprint = null, radio = SettingsRadioStatus(), permissions = false)
        compose.onNodeWithText("3 things need your attention").assertExists()
        compose.onNodeWithText("Not set. Other stations can't address you.").assertExists()
        snap("settings_1_fresh_overview")
        scrollTo("Group encryption")
        snap("settings_2_fresh_encryption")
    }

    @Test
    fun configuredStationIsReady() {
        settings = RadioSettings(callsign = "EK1ABC", beaconIntervalMin = 15, pttMode = com.radiome.core.audio.PttMode.SERIAL_RTS)
        val radio = SettingsRadioStatus(
            running = true,
            route = RouteInfo(RouteMode.CABLE, "USB audio", "USB audio"),
            restartPending = true,
            pttDevice = "CP2102",
        )
        show(fingerprint = "7F3A-91C2", radio = radio, permissions = true)
        compose.onNodeWithText("Ready to go").assertExists()
        snap("settings_3_ready_overview")
        scrollTo("Transmit keying (PTT)")
        snap("settings_4_ready_connection")
        scrollTo("Location and map")
        snap("settings_5_ready_location")
        scrollTo("Quick messages")
        snap("settings_6_ready_messages")
    }

    @Test
    fun overviewLineJumpsAndAdvancedExpands() {
        show(fingerprint = "7F3A-91C2", radio = SettingsRadioStatus(), permissions = true)
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.waitForIdle()
        scrollTo("Request missing chunks automatically")
        snap("settings_7_advanced")
        compose.onNodeWithText("Request missing chunks automatically").performClick()
        compose.waitForIdle()
        assertEquals(false, settings.autoNack)
        scrollTo("Restore advanced defaults")
        compose.onNodeWithText("Restore advanced defaults").performClick()
        compose.waitForIdle()
        assertEquals(true, settings.autoNack)
    }

    @Test
    fun quickMessagesCanBeAddedAndRemoved() {
        show(fingerprint = null, radio = SettingsRadioStatus(), permissions = true)
        scrollTo("New quick message")
        compose.onNodeWithText("New quick message").performTextInput("On my way")
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()
        assertEquals("On my way", settings.quickMessages.last())
        compose.onNodeWithText("Copy that").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(false, "Copy that" in settings.quickMessages)
    }

    @Test
    fun darkTheme() {
        settings = RadioSettings(callsign = "EK1ABC")
        show(fingerprint = null, radio = SettingsRadioStatus(), permissions = true, dark = true)
        snap("settings_8_dark")
    }
}
