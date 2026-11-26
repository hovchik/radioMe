package com.radiome.feature.transceiver.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves the tiles of a small area into osmdroid's tile cache, so the map shows it with no internet. Within the
 * OpenStreetMap tile policy: at most [MapGeo.MAX_SAVE_TILES] per save, one request at a time, the app's own user
 * agent, and tiles already in the cache are not fetched again. (osmdroid's own bulk downloader refuses the OSM
 * servers outright, hence this small one.)
 */
object AreaDownloader {
    data class Progress(val done: Int, val total: Int, val saved: Int, val failed: Int)

    /** How long saved tiles count as fresh. Viewed tiles keep the 30 days set in configureOsmdroid. */
    private const val KEEP_MS = 365L * 24 * 60 * 60 * 1000

    suspend fun save(plan: List<MapGeo.TileRange>, onProgress: (Progress) -> Unit): Progress = withContext(Dispatchers.IO) {
        val source = TileSourceFactory.MAPNIK as OnlineTileSourceBase
        val writer = SqlTileWriter()
        val total = plan.sumOf { it.count }
        var done = 0
        var saved = 0
        var failed = 0
        try {
            for (range in plan) for (x in range.xMin..range.xMax) for (y in range.yMin..range.yMax) {
                ensureActive()
                val index = MapTileIndex.getTileIndex(range.z, x, y)
                if (!writer.exists(source, index)) {
                    if (fetch(source, writer, index)) saved++ else failed++
                    delay(50) // be gentle with the volunteer-run servers
                }
                done++
                onProgress(Progress(done, total, saved, failed))
            }
        } finally {
            writer.onDetach()
        }
        Progress(done, total, saved, failed)
    }

    private fun fetch(source: OnlineTileSourceBase, writer: SqlTileWriter, index: Long): Boolean {
        val conn = try {
            URL(source.getTileURLString(index)).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return false
        }
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", Configuration.getInstance().userAgentValue)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            conn.inputStream.use { writer.saveFile(source, index, it, System.currentTimeMillis() + KEEP_MS) }
        } catch (e: IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
