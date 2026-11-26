package com.radiome.feature.transceiver.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.radiome.data.StationPosition
import com.radiome.feature.transceiver.Presenters
import com.radiome.feature.transceiver.i18n.tr
import com.radiome.feature.transceiver.map.AreaDownloader
import com.radiome.feature.transceiver.map.MapGeo
import com.radiome.feature.transceiver.map.MapLocation
import com.radiome.feature.transceiver.map.MapGeo.latLon
import com.radiome.feature.transceiver.map.OfflineMaps
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * Stations on an OpenStreetMap map. Every position shown here arrived sealed with the group key and was opened on
 * this phone. Markers carry the callsign and fade as fixes age, trails show where each station went in the last few
 * hours, and tapping one gives distance and bearing from you. Tiles are cached on disk as you browse, imported map
 * files cover areas never viewed online, and tile downloads can be turned off altogether.
 */
@Composable
fun MapScreen(
    positions: List<StationPosition>,
    trails: Map<String, List<MapGeo.LatLon>>,
    hasKey: Boolean,
    fingerprint: String?,
    now: Long,
    onSendPosition: () -> Unit,
    onMessage: (callsign: String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    remember { configureOsmdroid(context) }
    var map by remember { mutableStateOf<MapView?>(null) }
    val layers = remember { StationLayers() }
    var centered by remember { mutableStateOf(MapCamera.saved) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showTrails by remember { mutableStateOf(OfflineMaps.showTrails(context)) }
    var onlineTiles by remember { mutableStateOf(OfflineMaps.onlineTiles(context)) }
    var showOfflineDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var locationAllowed by remember { mutableStateOf(MapLocation.granted(context)) }
    var preciseLocation by remember { mutableStateOf(MapLocation.precise(context)) }
    var locationServicesOn by remember { mutableStateOf(MapLocation.servicesOn(context)) }
    // The user may change any of these in Android's settings and come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        locationAllowed = MapLocation.granted(context)
        preciseLocation = MapLocation.precise(context)
        locationServicesOn = MapLocation.servicesOn(context)
    }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf<AreaDownloader.Progress?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var saveResult by remember { mutableStateOf<String?>(null) }
    var liveFix by remember { mutableStateOf<MapGeo.LatLon?>(null) }
    var myLocation by remember { mutableStateOf<LiveLocationOverlay?>(null) }
    var followRequested by remember { mutableStateOf(false) }

    val me: MapGeo.LatLon? = liveFix ?: positions.firstOrNull { it.own }?.latLon()
    val selectedStation = positions.firstOrNull { MapGeo.key(it) == selected }
    val onSelect: (String?) -> Unit = { selected = it }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        locationAllowed = MapLocation.granted(context)
        preciseLocation = MapLocation.precise(context)
        locationServicesOn = MapLocation.servicesOn(context)
        if (granted.values.any { it }) followRequested = true
    }
    val askLocation = {
        PermissionPrompts.markAsked(context, MapLocation.PERMISSIONS.toList())
        locationLauncher.launch(MapLocation.PERMISSIONS)
    }
    val centerOnMe = {
        when {
            !locationAllowed -> askLocation()
            !locationServicesOn -> MapLocation.openLocationSettings(context)
            else -> { followRequested = true }
        }
    }
    val saveArea: () -> Unit = save@{
        if (saveJob?.isActive == true) return@save
        val view = map ?: return@save
        val box = view.boundingBox
        val plan = MapGeo.planSave(box.latNorth, box.latSouth, box.lonEast, box.lonWest, view.zoomLevelDouble) ?: return@save
        saveResult = null
        // Busy from the first tap, not from the first finished tile, so a second tap can't start another save.
        saving = AreaDownloader.Progress(0, plan.sumOf { it.count }, 0, 0)
        saveJob = scope.launch {
            try {
                val done = AreaDownloader.save(plan) { p -> view.post { saving = p } }
                saveResult = if (done.failed == 0) {
                    tr("Area saved: %d new tiles. It now works offline.", done.saved)
                } else {
                    tr("Saved %d tiles, %d failed. Check the internet connection and save again.", done.saved, done.failed)
                }
            } finally {
                saving = null
                saveJob = null
                view.tileProvider.clearTileCache()
                view.invalidate()
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        KeyBanner(hasKey, fingerprint)
        LocationBanner(
            allowed = locationAllowed,
            precise = preciseLocation,
            servicesOn = locationServicesOn,
            onAllow = askLocation,
            onTurnOn = { MapLocation.openLocationSettings(context) },
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setUseDataConnection(onlineTiles)
                        setMultiTouchControls(true)
                        isTilesScaledToDpi = true
                        MapCamera.restore(this)
                        overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                onSelect(null)
                                return false
                            }

                            override fun longPressHelper(p: GeoPoint?): Boolean = false
                        }))
                        overlays.add(layers.trails)
                        overlays.add(layers.markers)
                        overlays.add(ScaleBarOverlay(this).apply {
                            setCentred(true)
                            setAlignBottom(true)
                            setScaleBarOffset(resources.displayMetrics.widthPixels / 2, (12 * resources.displayMetrics.density).toInt())
                        })
                        overlays.add(CopyrightOverlay(ctx))
                        map = this
                    }
                },
                update = { view ->
                    // With a live fix the GPS dot is "you"; the last position you sent would be a second, older you.
                    val shown = if (liveFix != null) positions.filterNot { it.own } else positions
                    layers.render(view, shown, if (showTrails) trails else emptyMap(), now, selected, onSelect)
                },
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MapControl(tr("My location"), active = myLocation?.isFollowLocationEnabled == true) { centerOnMe() }
                MapControl(tr("Show all"), enabled = positions.isNotEmpty()) {
                    myLocation?.disableFollowLocation()
                    map?.let { fit(it, positions) }
                }
                MapControl(tr("Trails"), active = showTrails) {
                    showTrails = !showTrails
                    OfflineMaps.setShowTrails(context, showTrails)
                }
                MapControl(tr("Offline maps"), active = !onlineTiles) { showOfflineDialog = true }
            }

            saving?.let { p ->
                Surface(
                    Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 36.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 2.dp,
                ) {
                    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Saving map %d/%d", p.done, p.total), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { saveJob?.cancel() }) { Text(tr("Cancel")) }
                    }
                }
            }

            if (!onlineTiles) {
                Surface(
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    tonalElevation = 2.dp,
                ) {
                    Text(
                        tr("Offline: saved maps only"),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Button(
                onClick = onSendPosition,
                enabled = hasKey,
                modifier = Modifier.align(Alignment.BottomEnd).padding(start = 12.dp, end = 12.dp, bottom = 36.dp),
                elevation = ButtonDefaults.elevatedButtonElevation(),
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(tr("Send my position"))
            }
        }

        if (selectedStation != null) {
            StationDetails(
                p = selectedStation,
                me = me,
                now = now,
                onCenter = {
                    myLocation?.disableFollowLocation()
                    map?.controller?.animateTo(GeoPoint(selectedStation.position.latitude, selectedStation.position.longitude), 16.0, 600L)
                },
                onMessage = { onMessage(selectedStation.callsign) },
                onClose = { selected = null },
            )
        } else {
            StationList(
                positions = MapGeo.sortForList(positions, me),
                me = me,
                now = now,
                onPick = { p ->
                    selected = MapGeo.key(p)
                    myLocation?.disableFollowLocation()
                    map?.controller?.animateTo(GeoPoint(p.position.latitude, p.position.longitude), 15.0, 600L)
                },
                onClear = { confirmClear = true },
            )
        }
    }

    if (showOfflineDialog) {
        OfflineMapsDialog(
            onlineTiles = onlineTiles,
            onOnlineChange = {
                onlineTiles = it
                OfflineMaps.setOnlineTiles(context, it)
                // "Off" means no internet at all, including a save in progress.
                if (!it) saveJob?.cancel()
                map?.setUseDataConnection(it)
            },
            onFilesChanged = { map?.let { reloadTiles(it, onlineTiles) } },
            savePlan = remember(showOfflineDialog) {
                map?.let { MapGeo.planSave(it.boundingBox.latNorth, it.boundingBox.latSouth, it.boundingBox.lonEast, it.boundingBox.lonWest, it.zoomLevelDouble) }
            },
            saving = saving,
            saveResult = saveResult,
            onSave = saveArea,
            onCancelSave = { saveJob?.cancel() },
            onDismiss = { showOfflineDialog = false },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Clear positions?")) },
            text = { Text(tr("Removes every station and trail from this phone. New positions will appear as they arrive.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    selected = null
                    onClear()
                }) { Text(tr("Clear positions")) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel")) } },
        )
    }

    // First positions: frame them once; after that the user moves the map.
    LaunchedEffect(positions.isNotEmpty(), map) {
        val m = map
        if (!centered && m != null && positions.isNotEmpty()) {
            m.post { fit(m, positions) }
            centered = true
        }
    }

    // A station that was cleared or forgotten can't stay selected.
    LaunchedEffect(selectedStation == null) {
        if (selectedStation == null) selected = null
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
            view?.let { MapCamera.save(it) }
            view?.onDetach()
        }
    }

    // Live GPS dot, only while the map is on screen and in the foreground. LocationManager based: works offline.
    DisposableEffect(lifecycle, map, locationAllowed) {
        val view = map
        if (view == null || !locationAllowed) {
            onDispose { }
        } else {
            val overlay = LiveLocationOverlay(view) { fix -> view.post { liveFix = fix } }
            view.overlays.add(overlay)
            myLocation = overlay
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> overlay.enableMyLocation()
                    Lifecycle.Event.ON_PAUSE -> overlay.disableMyLocation()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) overlay.enableMyLocation()
            onDispose {
                lifecycle.removeObserver(observer)
                overlay.disableMyLocation()
                view.overlays.remove(overlay)
                if (myLocation === overlay) myLocation = null
            }
        }
    }

    LaunchedEffect(followRequested, myLocation) {
        val overlay = myLocation ?: return@LaunchedEffect
        val view = map ?: return@LaunchedEffect
        if (!followRequested) return@LaunchedEffect
        followRequested = false
        if (view.zoomLevelDouble < 14.0) view.controller.setZoom(16.0)
        overlay.enableFollowLocation()
        // No live fix yet: show the last position we sent while the GPS warms up.
        if (overlay.myLocation == null) {
            positions.firstOrNull { it.own }?.let { view.controller.animateTo(GeoPoint(it.position.latitude, it.position.longitude)) }
        }
    }
}

@Composable
private fun KeyBanner(hasKey: Boolean, fingerprint: String?) {
    var expanded by rememberSaveable { mutableStateOf(!hasKey) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (hasKey) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (hasKey) tr("Positions are end-to-end encrypted · key %s", fingerprint) else tr("No group key: positions can't be sent or read"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) {
                Text(
                    tr("Encrypted traffic is not allowed on amateur radio bands. Use licence-free (PMR446, FRS) or licensed private channels."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Why you are missing from the map, with the one tap that fixes it. Nothing shows when location works. */
@Composable
private fun LocationBanner(allowed: Boolean, precise: Boolean, servicesOn: Boolean, onAllow: () -> Unit, onTurnOn: () -> Unit) {
    val (text, button, action) = when {
        !allowed -> Triple(tr("Allow location to see yourself on the map."), tr("Allow"), onAllow)
        !servicesOn -> Triple(tr("Location is off on this phone, so the map can't find you."), tr("Turn on"), onTurnOn)
        !precise -> Triple(tr("Approximate location only: you can show up to 3 km off."), tr("Use precise"), onAllow)
        else -> return
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = action) { Text(button) }
        }
    }
}

@Composable
private fun MapControl(label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    if (active) {
        Button(onClick = onClick, enabled = enabled, contentPadding = padding) { Text(label, style = MaterialTheme.typography.labelMedium) }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = padding) { Text(label, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun FreshnessDot(p: StationPosition, now: Long) {
    val color = Color(stationColor(p, now))
    val description = when {
        p.own -> tr("You")
        else -> when (MapGeo.freshness(MapGeo.ageMs(p, now))) {
            MapGeo.Freshness.FRESH -> tr("Recent")
            MapGeo.Freshness.AGING -> tr("Getting old")
            MapGeo.Freshness.STALE -> tr("Old position")
        }
    }
    Box(Modifier.size(12.dp).background(color, CircleShape).semantics { contentDescription = description })
}

@Composable
private fun StationList(
    positions: List<StationPosition>,
    me: MapGeo.LatLon?,
    now: Long,
    onPick: (StationPosition) -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 200.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        if (positions.isEmpty()) {
            item {
                Text(
                    tr("No positions yet. Ask the group to send theirs."),
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(positions, key = { MapGeo.key(it) }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FreshnessDot(p, now)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(label(p), style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            Presenters.formatAge(MapGeo.ageMs(p, now)),
                            if (p.own) null else MapGeo.formatRange(me, p.latLon()),
                            p.position.speedMps?.takeIf { it >= 0.5 }?.let { MapGeo.formatSpeed(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (positions.isNotEmpty()) {
            item {
                TextButton(onClick = onClear, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Clear positions"))
                }
            }
        }
    }
}

@Composable
private fun StationDetails(
    p: StationPosition,
    me: MapGeo.LatLon?,
    now: Long,
    onCenter: () -> Unit,
    onMessage: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FreshnessDot(p, now)
            Spacer(Modifier.width(10.dp))
            Text(label(p), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = tr("Close")) }
        }
        Text(Presenters.formatCoordinates(p.position.latitude, p.position.longitude), style = MaterialTheme.typography.bodyMedium)
        if (!p.own) {
            Text(
                MapGeo.formatRange(me, p.latLon())?.let { tr("From you: %s", it) } ?: tr("Send your position or tap My location to see distance and bearing."),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            listOfNotNull(
                tr("Fix %s", Presenters.formatAge(MapGeo.ageMs(p, now))),
                p.position.accuracyM?.let { "±$it m" },
                p.snrDb?.let { "SNR %.0f dB".format(java.util.Locale.ROOT, it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        val motion = listOfNotNull(
            p.position.speedMps?.let { MapGeo.formatSpeed(it) },
            p.position.courseDeg?.takeIf { (p.position.speedMps ?: 0.0) >= 0.5 }?.let { "%s %d°".format(java.util.Locale.ROOT, MapGeo.compassPoint(it), it.toInt()) },
            p.position.altitudeM?.let { tr("altitude %d m", it) },
        )
        if (motion.isNotEmpty()) Text(motion.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!p.own && p.callsign.isNotBlank()) {
                Button(onClick = onMessage) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Message"))
                }
            }
            OutlinedButton(onClick = onCenter) { Text(tr("Center")) }
        }
    }
}

@Composable
private fun OfflineMapsDialog(
    onlineTiles: Boolean,
    onOnlineChange: (Boolean) -> Unit,
    onFilesChanged: () -> Unit,
    savePlan: List<MapGeo.TileRange>?,
    saving: AreaDownloader.Progress?,
    saveResult: String?,
    onSave: () -> Unit,
    onCancelSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val files = remember(revision) { OfflineMaps.list(context) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            status = tr("Importing…")
            scope.launch {
                status = when (val r = OfflineMaps.import(context, uri)) {
                    is OfflineMaps.ImportResult.Imported -> tr("Imported %s", r.name)
                    OfflineMaps.ImportResult.NotAMapFile -> tr("Not a map file. Use .mbtiles, .gemf, .sqlite or .zip.")
                    is OfflineMaps.ImportResult.Failed -> tr("Import failed: %s", r.reason)
                }
                busy = false
                revision++
                onFilesChanged()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Offline maps")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Areas you have viewed stay available offline for 30 days. For places you haven't viewed yet, import a map file (MBTiles, GEMF, osmdroid SQLite or ZIP) made on a computer."),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Download map tiles"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("Off: no internet use, and the tile server can't see which area you look at."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = onlineTiles, onCheckedChange = onOnlineChange)
                }
                HorizontalDivider()
                Text(tr("Save the area on screen"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        !onlineTiles -> tr("Turn on map downloads to save an area.")
                        savePlan == null -> tr("This area is too big to save at once. Zoom in, then save it in parts.")
                        else -> tr(
                            "About %d tiles, zoom %d to %d. OpenStreetMap allows up to 250 tiles per save: zoom in for street detail, out for a wider area.",
                            savePlan.sumOf { it.count }, savePlan.first().z, savePlan.last().z,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (saving != null) {
                    Text(tr("Saving %d of %d…", saving.done, saving.total), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { if (saving.total == 0) 0f else saving.done.toFloat() / saving.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onCancelSave) { Text(tr("Cancel")) }
                } else {
                    FilledTonalButton(onClick = onSave, enabled = onlineTiles && savePlan != null) { Text(tr("Save this area")) }
                }
                saveResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                if (files.isEmpty()) {
                    Text(tr("No map files imported."), style = MaterialTheme.typography.bodySmall)
                }
                files.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium)
                            Text(formatSize(f), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(enabled = !busy, onClick = {
                            OfflineMaps.delete(f)
                            revision++
                            onFilesChanged()
                        }) { Icon(Icons.Filled.Delete, contentDescription = tr("Delete %s", f.name)) }
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Done")) } },
        dismissButton = {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(tr("Import map file")) }
        },
    )
}

private fun formatSize(f: File): String {
    val mb = f.length() / (1024.0 * 1024.0)
    return if (mb >= 1) "%.1f MB".format(java.util.Locale.ROOT, mb) else "%d KB".format(java.util.Locale.ROOT, f.length() / 1024)
}

private fun label(p: StationPosition): String = when {
    p.own -> tr("You") + p.callsign.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    else -> p.callsign
}

private const val COLOR_OWN = 0xFF1E88E5.toInt()
private const val COLOR_FRESH = 0xFF2E7D32.toInt()
private const val COLOR_AGING = 0xFFEF8F00.toInt()
private const val COLOR_STALE = 0xFF9E9E9E.toInt()

private fun stationColor(p: StationPosition, now: Long): Int = when {
    p.own -> COLOR_OWN
    else -> when (MapGeo.freshness(MapGeo.ageMs(p, now))) {
        MapGeo.Freshness.FRESH -> COLOR_FRESH
        MapGeo.Freshness.AGING -> COLOR_AGING
        MapGeo.Freshness.STALE -> COLOR_STALE
    }
}

/**
 * The stations' trails and markers, in two folders so the map's other overlays keep their place. Rebuilt only when
 * something visible changes: the ticking clock alone redraws nothing until a fix crosses a freshness step.
 */
private class StationLayers {
    val trails = FolderOverlay()
    val markers = FolderOverlay()
    private var lastSignature: Any? = null
    private val icons = HashMap<String, StationIcon>()

    fun render(
        view: MapView,
        positions: List<StationPosition>,
        trailPoints: Map<String, List<MapGeo.LatLon>>,
        now: Long,
        selected: String?,
        onSelect: (String?) -> Unit,
    ) {
        val colors = positions.map { stationColor(it, now) }
        val signature = listOf(positions, trailPoints, colors, selected)
        if (signature == lastSignature) return
        lastSignature = signature

        trails.items.clear()
        markers.items.clear()
        val density = view.resources.displayMetrics.density
        val colorByKey = positions.zip(colors).associate { (p, c) -> MapGeo.key(p) to c }
        for ((key, points) in trailPoints) {
            val color = colorByKey[key] ?: COLOR_STALE
            trails.add(Polyline(view).apply {
                setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color = (color and 0x00FFFFFF) or (0xA0 shl 24)
                outlinePaint.strokeWidth = 4 * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                // Let taps on a trail fall through to the map, which clears the selection.
                setOnClickListener { _, _, _ -> false }
            })
        }
        // The selected station is drawn last, on top of the others.
        for ((p, color) in positions.zip(colors).sortedBy { MapGeo.key(it.first) == selected }) {
            val key = MapGeo.key(p)
            val isSelected = key == selected
            val text = label(p)
            val icon = icons.getOrPut("$text|$color|$isSelected") { stationIcon(view.resources, text, color, isSelected) }
            markers.add(Marker(view).apply {
                position = GeoPoint(p.position.latitude, p.position.longitude)
                this.icon = icon.drawable
                setAnchor(Marker.ANCHOR_CENTER, icon.anchorV)
                title = text
                setOnMarkerClickListener { _, _ ->
                    onSelect(key)
                    true
                }
            })
        }
        if (icons.size > 200) icons.clear()
        view.invalidate()
    }
}

private class StationIcon(val drawable: BitmapDrawable, val anchorV: Float)

/** A coloured dot with the callsign in a small label under it, so stations are told apart without tapping. */
private fun stationIcon(res: Resources, label: String, color: Int, selected: Boolean): StationIcon {
    val d = res.displayMetrics.density
    val radius = (if (selected) 11f else 8f) * d
    val stroke = (if (selected) 3.5f else 2.5f) * d
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * d * res.configuration.fontScale
        this.color = 0xFF202020.toInt()
        isFakeBoldText = selected
    }
    val pad = 4f * d
    val gap = 2f * d
    val textWidth = if (label.isEmpty()) 0f else text.measureText(label)
    val pillHeight = if (label.isEmpty()) 0f else text.descent() - text.ascent() + 2 * pad
    val dot = 2 * (radius + stroke)
    val width = ceil(max(dot, textWidth + 2 * pad)).toInt()
    val height = ceil(dot + (if (label.isEmpty()) 0f else gap + pillHeight)).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = width / 2f
    val cy = radius + stroke
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        this.color = if (selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    canvas.drawCircle(cx, cy, radius, fill)
    canvas.drawCircle(cx, cy, radius, ring)
    if (label.isNotEmpty()) {
        val top = dot + gap
        val rect = RectF(cx - textWidth / 2 - pad, top, cx + textWidth / 2 + pad, top + pillHeight)
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xE6FFFFFF.toInt() }
        canvas.drawRoundRect(rect, pillHeight / 2, pillHeight / 2, pill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = d
            this.color = color
        }
        canvas.drawRoundRect(rect, pillHeight / 2, pillHeight / 2, border)
        canvas.drawText(label, cx - textWidth / 2, top + pad - text.ascent(), text)
    }
    return StationIcon(BitmapDrawable(res, bitmap), cy / height)
}

/** osmdroid's "my location" dot that also reports each fix, for distance and bearing to the other stations. */
private class LiveLocationOverlay(view: MapView, private val onFix: (MapGeo.LatLon) -> Unit) :
    MyLocationNewOverlay(
        GpsMyLocationProvider(view.context).apply {
            setLocationUpdateMinTime(2_000L)
            setLocationUpdateMinDistance(3f)
        },
        view,
    ) {
    override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
        super.onLocationChanged(location, source)
        location?.let { onFix(MapGeo.LatLon(it.latitude, it.longitude)) }
    }
}

/** Where the map was looking, kept while the app runs so switching tabs doesn't throw the view back to the world. */
private object MapCamera {
    private var center: GeoPoint? = null
    private var zoom: Double = 3.0
    val saved: Boolean get() = center != null

    fun save(view: MapView) {
        center = GeoPoint(view.mapCenter.latitude, view.mapCenter.longitude)
        zoom = view.zoomLevelDouble
    }

    fun restore(view: MapView) {
        view.controller.setZoom(zoom)
        center?.let { view.controller.setCenter(it) }
    }
}

private fun fit(view: MapView, positions: List<StationPosition>) {
    if (positions.isEmpty()) return
    if (positions.size == 1) {
        view.controller.setZoom(15.0)
        view.controller.setCenter(GeoPoint(positions[0].position.latitude, positions[0].position.longitude))
        return
    }
    val points = positions.map { GeoPoint(it.position.latitude, it.position.longitude) }
    val box = BoundingBox.fromGeoPoints(points)
    // Stations at (nearly) the same spot make a zero-size box, which would zoom in to the maximum.
    if (box.latitudeSpan < 1e-4 && box.longitudeSpanWithDateLine < 1e-4) {
        view.controller.setZoom(17.0)
        view.controller.setCenter(box.centerWithDateLine)
        return
    }
    view.zoomToBoundingBox(box.increaseByScale(1.4f), true, 64)
}

/** A new tile provider rescans the map folder, so imported or deleted map files take effect right away. */
private fun reloadTiles(view: MapView, online: Boolean) {
    view.setTileProvider(MapTileProviderBasic(view.context.applicationContext, TileSourceFactory.MAPNIK))
    view.setUseDataConnection(online)
    view.invalidate()
}

/**
 * osmdroid setup: identify the app to the OSM tile servers (their usage policy requires a real user agent) and keep
 * a large tile cache in app storage, with tiles treated as fresh for 30 days so viewed areas work offline. Imported
 * map files sit in the base folder, where osmdroid looks for archives.
 */
private fun configureOsmdroid(context: Context) {
    val cfg = Configuration.getInstance()
    cfg.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    cfg.userAgentValue = context.packageName
    val base = OfflineMaps.folder(context)
    cfg.osmdroidBasePath = base
    cfg.osmdroidTileCache = File(base, "tiles")
    cfg.tileFileSystemCacheMaxBytes = 600L * 1024 * 1024
    cfg.tileFileSystemCacheTrimBytes = 500L * 1024 * 1024
    cfg.expirationExtendedDuration = 30L * 24 * 60 * 60 * 1000
}
