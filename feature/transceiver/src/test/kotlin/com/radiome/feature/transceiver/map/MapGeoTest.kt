package com.radiome.feature.transceiver.map

import com.radiome.core.protocol.payload.Position
import com.radiome.data.StationPosition
import com.radiome.feature.transceiver.map.MapGeo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGeoTest {
    private val yerevan = LatLon(40.1792, 44.4991)
    private val gyumri = LatLon(40.7894, 43.8475)

    private fun station(callsign: String, lat: Double, lon: Double, timeSec: Long, own: Boolean = false) =
        StationPosition(callsign, Position(lat, lon, null, null, null, null, timeSec), receivedAt = timeSec * 1000, own = own, snrDb = null)

    @Test
    fun distanceMatchesKnownCities() {
        // Yerevan to Gyumri is about 88 km in a straight line.
        val d = MapGeo.distanceM(yerevan, gyumri)
        assertTrue("$d", d in 84_000.0..92_000.0)
        assertEquals(0.0, MapGeo.distanceM(yerevan, yerevan), 1e-6)
        // One degree of latitude is about 111.2 km everywhere.
        assertEquals(111_195.0, MapGeo.distanceM(LatLon(0.0, 10.0), LatLon(1.0, 10.0)), 50.0)
    }

    @Test
    fun bearingPointsTheRightWay() {
        assertEquals(0.0, MapGeo.bearingDeg(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1e-6)
        assertEquals(90.0, MapGeo.bearingDeg(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 1e-6)
        assertEquals(180.0, MapGeo.bearingDeg(LatLon(1.0, 0.0), LatLon(0.0, 0.0)), 1e-6)
        assertEquals(270.0, MapGeo.bearingDeg(LatLon(0.0, 1.0), LatLon(0.0, 0.0)), 1e-6)
        // Gyumri is north-west of Yerevan.
        assertEquals("NW", MapGeo.compassPoint(MapGeo.bearingDeg(yerevan, gyumri)))
    }

    @Test
    fun compassPointsWrapAround() {
        assertEquals("N", MapGeo.compassPoint(0.0))
        assertEquals("N", MapGeo.compassPoint(359.0))
        assertEquals("N", MapGeo.compassPoint(-10.0))
        assertEquals("NE", MapGeo.compassPoint(40.0))
        assertEquals("E", MapGeo.compassPoint(90.0))
        assertEquals("SW", MapGeo.compassPoint(225.0))
        assertEquals("W", MapGeo.compassPoint(290.0))
    }

    @Test
    fun distancesAreShortAndReadable() {
        assertEquals("0 m", MapGeo.formatDistance(0.2))
        assertEquals("850 m", MapGeo.formatDistance(850.4))
        assertEquals("2.4 km", MapGeo.formatDistance(2_449.0))
        assertEquals("88 km", MapGeo.formatDistance(87_600.0))
        assertEquals("36 km/h", MapGeo.formatSpeed(10.0))
        assertNull(MapGeo.formatRange(null, yerevan))
        assertEquals("1.0 km N · 0°", MapGeo.formatRange(LatLon(0.0, 0.0), LatLon(0.009, 0.0)))
    }

    @Test
    fun fixesFadeWithAge() {
        assertEquals(MapGeo.Freshness.FRESH, MapGeo.freshness(0))
        assertEquals(MapGeo.Freshness.FRESH, MapGeo.freshness(MapGeo.FRESH_MS - 1))
        assertEquals(MapGeo.Freshness.AGING, MapGeo.freshness(MapGeo.FRESH_MS))
        assertEquals(MapGeo.Freshness.STALE, MapGeo.freshness(MapGeo.STALE_MS))
    }

    @Test
    fun listPutsUsFirstThenNearest() {
        val me = station("ME", 40.0, 44.0, 100, own = true)
        val far = station("FAR", 41.0, 44.0, 300)
        val near = station("NEAR", 40.01, 44.0, 200)
        val sorted = MapGeo.sortForList(listOf(far, near, me), LatLon(40.0, 44.0))
        assertEquals(listOf("ME", "NEAR", "FAR"), sorted.map { it.callsign })
        // Without our own position: most recently heard first.
        val byTime = MapGeo.sortForList(listOf(near, far), null)
        assertEquals(listOf("FAR", "NEAR"), byTime.map { it.callsign })
    }

    @Test
    fun trailsGroupByStationInTimeOrder() {
        val fixes = listOf(
            station("A", 40.0, 44.0, 30),
            station("A", 40.1, 44.0, 10),
            station("A", 40.2, 44.0, 20),
            station("B", 41.0, 44.0, 10),
            station("A", 39.0, 44.0, 5, own = true),
            station("A", 39.1, 44.0, 6, own = true),
        )
        val trails = MapGeo.trails(fixes)
        assertEquals(setOf("A", "me:A"), trails.keys) // B has a single fix: no trail
        assertEquals(listOf(40.1, 40.2, 40.0), trails.getValue("A").map { it.latitude })
        assertEquals(listOf(40.2, 40.0), MapGeo.trails(fixes, maxPoints = 2).getValue("A").map { it.latitude })
    }

    @Test
    fun importedFileNamesAreSafe() {
        assertEquals("armenia.mbtiles", OfflineMaps.sanitize("armenia.mbtiles"))
        assertEquals("my_map__1_.zip", OfflineMaps.sanitize("my map (1).zip"))
        assertEquals("passwd", OfflineMaps.sanitize("../../etc/passwd"))
        assertEquals("map", OfflineMaps.sanitize(""))
    }

    @Test
    fun tileNumbersMatchTheSlippyMapScheme() {
        assertEquals(0, MapGeo.tileX(-180.0, 0))
        assertEquals(0, MapGeo.tileY(0.0, 0))
        // Yerevan at zoom 10.
        assertEquals(638, MapGeo.tileX(44.4991, 10))
        assertEquals(386, MapGeo.tileY(40.1792, 10))
        // Edges clamp instead of leaving the grid.
        assertEquals(1023, MapGeo.tileX(180.0, 10))
        assertEquals(1023, MapGeo.tileY(-89.0, 10))
    }

    @Test
    fun savePlanStaysWithinTheTilePolicy() {
        // A few streets of Yerevan at zoom 15.
        val plan = MapGeo.planSave(40.19, 40.17, 44.52, 44.49, 15.0)!!
        assertTrue(plan.sumOf { it.count } <= MapGeo.MAX_SAVE_TILES)
        assertTrue(plan.any { it.z == 15 })
        assertTrue(plan.any { it.z > 15 }) // closer zooms fit too
        assertEquals(plan.map { it.z }.sorted(), plan.map { it.z })
        // The whole country at street zoom is far too much: zoom in first.
        assertNull(MapGeo.planSave(41.3, 38.8, 46.6, 43.4, 15.0))
        // Zoomed out, the same country fits.
        assertTrue(MapGeo.planSave(41.3, 38.8, 46.6, 43.4, 8.0) != null)
    }
}
