package com.radiome.feature.transceiver.map

import com.radiome.core.protocol.payload.Position
import com.radiome.data.StationPosition
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain geometry and presentation rules for the map, kept free of Android so they can be unit tested. */
object MapGeo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** A fix younger than this is shown at full strength. */
    const val FRESH_MS = 15 * 60_000L

    /** A fix older than this is shown greyed out: the station may have moved a long way since. */
    const val STALE_MS = 60 * 60_000L

    /** How far back the trails on the map reach. */
    const val TRAIL_WINDOW_MS = 6 * 60 * 60_000L

    /** Points kept per trail, newest ones. */
    const val TRAIL_MAX_POINTS = 200

    enum class Freshness { FRESH, AGING, STALE }

    data class LatLon(val latitude: Double, val longitude: Double)

    fun freshness(ageMs: Long): Freshness = when {
        ageMs < FRESH_MS -> Freshness.FRESH
        ageMs < STALE_MS -> Freshness.AGING
        else -> Freshness.STALE
    }

    /** Stable identity of a station on the map: our own fixes never mix with a member who uses the same callsign. */
    fun key(p: StationPosition): String = (if (p.own) "me:" else "") + p.callsign

    fun ageMs(p: StationPosition, now: Long): Long = (now - p.position.timeSec * 1000).coerceAtLeast(0)

    /** Great-circle distance in metres (haversine). */
    fun distanceM(from: LatLon, to: LatLon): Double {
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from [from] to [to], degrees clockwise from true north, 0 until 360. */
    fun bearingDeg(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Eight-point compass name of a bearing. */
    fun compassPoint(deg: Double): String = POINTS[(((deg % 360 + 360) % 360) / 45).roundToInt() % 8]

    fun formatDistance(m: Double): String = when {
        m < 1000 -> "%d m".format(Locale.ROOT, m.roundToInt())
        m < 10_000 -> "%.1f km".format(Locale.ROOT, m / 1000)
        else -> "%d km".format(Locale.ROOT, (m / 1000).roundToInt())
    }

    /** "2.4 km NE · 41°", or null without a position of our own to measure from. */
    fun formatRange(me: LatLon?, to: LatLon): String? {
        me ?: return null
        val d = distanceM(me, to)
        if (d < 1) return formatDistance(d)
        val b = bearingDeg(me, to)
        return "%s %s · %d°".format(Locale.ROOT, formatDistance(d), compassPoint(b), b.roundToInt() % 360)
    }

    /** "12 km/h" from metres per second. */
    fun formatSpeed(mps: Double): String = "%d km/h".format(Locale.ROOT, (mps * 3.6).roundToInt())

    /**
     * Order for the station list: our own fix first, then the others nearest first when we know where we are,
     * otherwise most recently heard first.
     */
    fun sortForList(positions: List<StationPosition>, me: LatLon?): List<StationPosition> {
        val (own, others) = positions.partition { it.own }
        val sorted = if (me != null) {
            others.sortedBy { distanceM(me, it.latLon()) }
        } else {
            others.sortedByDescending { it.position.timeSec }
        }
        return own + sorted
    }

    /**
     * Trails from recent fixes, keyed like [key]: each station's fixes in time order, at most [maxPoints] of the
     * newest. Stations with a single fix have no trail.
     */
    fun trails(fixes: List<StationPosition>, maxPoints: Int = TRAIL_MAX_POINTS): Map<String, List<LatLon>> =
        fixes.groupBy(::key)
            .mapValues { (_, list) -> list.sortedBy { it.position.timeSec }.takeLast(maxPoints).map { it.latLon() } }
            .filterValues { it.size >= 2 }

    fun StationPosition.latLon(): LatLon = position.latLon()

    fun Position.latLon(): LatLon = LatLon(latitude, longitude)

    // ------------------------------------------------------------------------------------- saving an area offline

    /**
     * OpenStreetMap's tile policy forbids downloading "significantly more than 250 tiles" for offline use, so one
     * save never fetches more than this.
     */
    const val MAX_SAVE_TILES = 250

    /** Deepest zoom saved: street level. */
    const val MAX_SAVE_ZOOM = 18

    /** Web Mercator stops here. */
    private const val MAX_LAT = 85.05112878

    /** Tiles x in [xMin, xMax] and y in [yMin, yMax] at zoom [z]. */
    data class TileRange(val z: Int, val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int) {
        val count: Int get() = (xMax - xMin + 1) * (yMax - yMin + 1)
    }

    fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun tileRange(north: Double, south: Double, east: Double, west: Double, z: Int): TileRange {
        // A view across the date line (west > east) is saved as its larger, eastern part.
        val w = if (west > east) -180.0 else west
        return TileRange(z, tileX(w, z), tileX(east, z), tileY(north, z), tileY(south, z))
    }

    /**
     * What "save this area" fetches: the zoom on screen, up to four wider zooms for context, then as many closer
     * zooms as fit in [maxTiles]. Null when the area on screen alone is already too big: zoom in first.
     */
    fun planSave(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        zoom: Double,
        maxTiles: Int = MAX_SAVE_TILES,
        maxZoom: Int = MAX_SAVE_ZOOM,
    ): List<TileRange>? {
        val z0 = floor(zoom).toInt().coerceIn(0, maxZoom)
        val first = tileRange(north, south, east, west, z0)
        if (first.count > maxTiles) return null
        val plan = mutableListOf(first)
        var total = first.count
        for (z in (z0 - 1) downTo maxOf(0, z0 - 4)) {
            val r = tileRange(north, south, east, west, z)
            if (total + r.count > maxTiles) break
            plan += r
            total += r.count
        }
        for (z in (z0 + 1)..maxZoom) {
            val r = tileRange(north, south, east, west, z)
            if (total + r.count > maxTiles) break
            plan += r
            total += r.count
        }
        return plan.sortedBy { it.z }
    }
}
