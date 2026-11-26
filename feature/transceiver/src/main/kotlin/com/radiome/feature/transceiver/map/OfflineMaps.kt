package com.radiome.feature.transceiver.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Map files for areas the phone has never been online in. osmdroid reads tile archives (MBTiles, GEMF, its own
 * SQLite export, or a ZIP of `Mapnik/z/x/y.png`) straight from its base folder, so importing one is a copy into
 * that folder and a reload of the map's tile provider.
 */
object OfflineMaps {
    val EXTENSIONS = setOf("mbtiles", "gemf", "sqlite", "zip")

    private const val PREFS = "map"
    private const val KEY_ONLINE = "online_tiles"
    private const val KEY_TRAILS = "show_trails"

    /** osmdroid's base path; see configureOsmdroid in MapScreen. The tile cache lives in its `tiles` subfolder. */
    fun folder(context: Context): File = File(context.filesDir, "osmdroid")

    fun list(context: Context): List<File> =
        folder(context).listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            .sortedBy { it.name.lowercase() }

    sealed interface ImportResult {
        data class Imported(val name: String) : ImportResult
        data object NotAMapFile : ImportResult
        data class Failed(val reason: String) : ImportResult
    }

    /** Copies the picked file into the map folder. The copy goes to a temporary name first, so a failure leaves nothing half-written. */
    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = sanitize(displayName(context, uri) ?: uri.lastPathSegment ?: "map")
        if (File(name).extension.lowercase() !in EXTENSIONS) return@withContext ImportResult.NotAMapFile
        val dir = folder(context).apply { mkdirs() }
        val target = File(dir, name)
        val partial = File(dir, "$name.part")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't open the file")
            input.use { src -> partial.outputStream().use { src.copyTo(it, 64 * 1024) } }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IOException("can't save the file")
            ImportResult.Imported(name)
        } catch (e: IOException) {
            partial.delete()
            ImportResult.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            partial.delete()
            ImportResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun delete(file: File): Boolean = file.delete()

    /** Whether the map may fetch tiles it doesn't have. Off keeps the phone silent: no tile requests reveal where you look. */
    fun onlineTiles(context: Context): Boolean = prefs(context).getBoolean(KEY_ONLINE, true)

    fun setOnlineTiles(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ONLINE, on).apply()

    fun showTrails(context: Context): Boolean = prefs(context).getBoolean(KEY_TRAILS, true)

    fun setShowTrails(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_TRAILS, on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Keeps the name readable but safe as a single file name in our folder. */
    internal fun sanitize(name: String): String {
        val clean = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
        if (!clean.contains('.')) return clean.ifEmpty { "map" }.take(80)
        val base = clean.substringBeforeLast('.').ifEmpty { "map" }.take(70)
        return base + "." + clean.substringAfterLast('.').take(10)
    }
}
