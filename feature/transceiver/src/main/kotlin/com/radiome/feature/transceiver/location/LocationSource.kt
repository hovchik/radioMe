package com.radiome.feature.transceiver.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import com.radiome.core.protocol.payload.Position
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Where the phone is. Faked in tests. */
interface LocationSource {
    fun hasPermission(): Boolean

    /** A fresh fix, or a recent last-known one, or null (no permission, location off, no fix in time). */
    suspend fun current(): Position?
}

/** LocationManager only (no Play services), so it works on de-Googled phones and offline. */
class AndroidLocationSource(context: Context) : LocationSource {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun current(): Position? {
        if (!hasPermission() || manager == null) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        for (p in providers) {
            val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) { fresh(p) }
            if (fix != null) return fix.toPosition()
        }
        val now = System.currentTimeMillis()
        return providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time < LAST_KNOWN_MAX_AGE_MS }
            .maxByOrNull { it.time }
            ?.toPosition()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        try {
            LocationManagerCompat.getCurrentLocation(manager!!, provider, signal, executor) { cont.resume(it) }
        } catch (e: RuntimeException) {
            cont.resume(null)
        }
    }

    private fun Location.toPosition() = Position(
        latitude = latitude,
        longitude = longitude,
        altitudeM = if (hasAltitude()) altitude.toInt() else null,
        accuracyM = if (hasAccuracy()) accuracy.toInt() else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        courseDeg = if (hasBearing()) bearing.toDouble() else null,
        timeSec = time / 1000,
    )

    private companion object {
        const val FIX_TIMEOUT_MS = 30_000L
        const val LAST_KNOWN_MAX_AGE_MS = 10 * 60_000L
    }
}
