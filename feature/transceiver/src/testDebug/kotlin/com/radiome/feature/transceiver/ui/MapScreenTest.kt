package com.radiome.feature.transceiver.ui

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.radiome.core.protocol.payload.Position
import com.radiome.data.StationPosition
import com.radiome.feature.transceiver.map.MapGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the map with stations on it, the state right after sending or receiving a position. */
@RunWith(RobolectricTestRunner::class)
// A typical phone held upright (Robolectric defaults to a 320x470 dp screen, too short for the list under the map).
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MapScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val nowSec = now / 1000

    private fun own() = StationPosition("ME1", Position(40.1792, 44.4991, 990, 8, 0.0, null, nowSec - 30), now - 30_000, own = true, snrDb = null)
    private fun other() = StationPosition("K1ABC", Position(40.1810, 44.5100, 1000, 12, 3.0, 90.0, nowSec - 120), now - 100_000, own = false, snrDb = 7.5)

    private fun show(
        positions: List<StationPosition>,
        trails: Map<String, List<MapGeo.LatLon>> = emptyMap(),
        hasKey: Boolean = true,
        send: SendPositionUi = SendPositionUi(),
    ) {
        compose.setContent {
            MapScreen(
                positions = positions,
                trails = trails,
                hasKey = hasKey,
                send = send,
                fingerprint = "AB12",
                now = now,
                onSendPosition = {},
                onMessage = {},
                onClear = {},
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun showsOwnPositionAfterSending() {
        show(listOf(own()))
        compose.onNodeWithText("You (ME1)").assertExists()
    }

    @Test
    fun showsAndOpensAReceivedPosition() {
        val trail = mapOf("K1ABC" to listOf(MapGeo.LatLon(40.18, 44.50), MapGeo.LatLon(40.181, 44.51)))
        show(listOf(other(), own()), trail)
        compose.onNodeWithText("K1ABC").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Message").assertExists()
        compose.onNodeWithText("Center").performClick()
        compose.waitForIdle()
    }

    @Test
    fun showsTwoStationsAtTheSameSpot() {
        val same = other().copy(position = own().position.copy(timeSec = nowSec - 10))
        show(listOf(own(), same))
        compose.onNodeWithContentDescription("Show all").performClick()
        compose.waitForIdle()
    }

    @Test
    fun mapIsASquareAboveThePositions() {
        show(listOf(own(), other()))
        val map = compose.onNodeWithTag("map").getBoundsInRoot()
        assertEquals(map.right - map.left, map.bottom - map.top)
        val list = compose.onNodeWithText("K1ABC").getBoundsInRoot()
        assertTrue("the station list starts below the map", list.top >= map.bottom)
    }

    @Test
    fun sendButtonSaysWhatATapDoes() {
        show(listOf(own()), send = SendPositionUi(radioOn = false))
        compose.onNodeWithText("Turn radio on and send").assertExists()
    }

    @Test
    fun sendButtonAsksForAKeyFirst() {
        show(emptyList(), hasKey = false)
        compose.onNodeWithText("Set group key").assertExists()
    }

    @Test
    fun sendButtonShowsProgress() {
        show(listOf(own()), send = SendPositionUi(sending = true))
        compose.onNodeWithText("Sending position…").assertExists()
    }
}
