package com.radiome.feature.transceiver.map

import com.radiome.core.protocol.payload.Position
import com.radiome.data.StationPosition
import java.util.Locale
import kotlin.math.atan2
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
}
