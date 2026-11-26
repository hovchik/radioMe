package com.radiome.feature.transceiver.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.radiome.data.StationPosition
import com.radiome.feature.transceiver.Presenters
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * Stations on an OpenStreetMap map. Every position shown here arrived sealed with the group key and was opened on
 * this phone. Tiles are cached on disk as you browse, so areas you looked at while online keep working offline.
 */
@Composable
fun MapScreen(
    positions: List<StationPosition>,
    hasKey: Boolean,
    fingerprint: String?,
    now: Long,
    onSendPosition: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    remember { configureOsmdroid(context) }
    var map by remember { mutableStateOf<MapView?>(null) }
    var centered by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (hasKey) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (hasKey) "Positions are end-to-end encrypted · key $fingerprint" else "No group key: positions can't be sent or read",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Encrypted traffic is not allowed on amateur radio bands. Use licence-free (PMR446, FRS) or licensed private channels.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSendPosition, enabled = hasKey) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Send my position")
                    }
                    OutlinedButton(onClick = { map?.let { fit(it, positions) } }, enabled = positions.isNotEmpty()) { Text("Show all") }
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(3.0)
                    overlays.add(CopyrightOverlay(ctx))
                    map = this
                }
            },
            update = { view -> showMarkers(view, positions, now) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 180.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (positions.isEmpty()) {
                item { Text("No positions yet. Ask the group to send theirs.", style = MaterialTheme.typography.bodySmall) }
            }
            items(positions, key = { (if (it.own) "me:" else "") + it.callsign }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label(p), style = MaterialTheme.typography.titleSmall)
                        Text(
                            Presenters.formatCoordinates(p.position.latitude, p.position.longitude) + " · " +
                                Presenters.formatAge(now - p.position.timeSec * 1000) +
                                (p.position.accuracyM?.let { " · ±$it m" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = {
                        map?.controller?.animateTo(GeoPoint(p.position.latitude, p.position.longitude), 15.0, 600L)
                    }) { Text("Show") }
                }
            }
            if (positions.isNotEmpty()) item { OutlinedButton(onClick = onClear) { Text("Clear positions") } }
        }
    }

    // First positions: frame them once; after that the user moves the map.
    LaunchedEffect(positions.isNotEmpty(), map) {
        val m = map
        if (!centered && m != null && positions.isNotEmpty()) {
            m.post { fit(m, positions) }
            centered = true
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        // This effect's own view: when `map` goes from null to the new view, the old effect must not detach it.
        val view = map
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view?.onDetach()
        }
    }
}

private fun label(p: StationPosition): String = when {
    p.own -> "You" + p.callsign.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    else -> p.callsign
}

private fun showMarkers(view: MapView, positions: List<StationPosition>, now: Long) {
    view.overlays.removeAll { it is Marker }
    for (p in positions) {
        view.overlays += Marker(view).apply {
            position = GeoPoint(p.position.latitude, p.position.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = label(p)
            snippet = Presenters.formatAge(now - p.position.timeSec * 1000) +
                (p.position.accuracyM?.let { " · ±$it m" } ?: "") +
                (p.snrDb?.let { " · SNR %.0f dB".format(it) } ?: "")
            alpha = if (p.own) 0.7f else 1f
        }
    }
    view.invalidate()
}

private fun fit(view: MapView, positions: List<StationPosition>) {
    if (positions.isEmpty()) return
    if (positions.size == 1) {
        view.controller.setZoom(15.0)
        view.controller.setCenter(GeoPoint(positions[0].position.latitude, positions[0].position.longitude))
        return
    }
    val points = positions.map { GeoPoint(it.position.latitude, it.position.longitude) }
    view.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.4f), true, 64)
}

/**
 * osmdroid setup: identify the app to the OSM tile servers (their usage policy requires a real user agent) and keep
 * a large tile cache in app storage, with tiles treated as fresh for 30 days so viewed areas work offline.
 */
private fun configureOsmdroid(context: Context) {
    val cfg = Configuration.getInstance()
    cfg.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    cfg.userAgentValue = context.packageName
    val base = File(context.filesDir, "osmdroid")
    cfg.osmdroidBasePath = base
    cfg.osmdroidTileCache = File(base, "tiles")
    cfg.tileFileSystemCacheMaxBytes = 600L * 1024 * 1024
    cfg.tileFileSystemCacheTrimBytes = 500L * 1024 * 1024
    cfg.expirationExtendedDuration = 30L * 24 * 60 * 60 * 1000
}
