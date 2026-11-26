package com.radiome.feature.transceiver.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reading picked documents and writing files out through the system's document and media APIs. */
internal object FileIo {
    /** A picked document: [bytes] is null when it is larger than the limit ([size] then says how large). */
    class Picked(val name: String, val bytes: ByteArray?, val size: Long)

    /** Reads a picked document, at most [maxBytes]; null when it cannot be read at all. */
    suspend fun read(context: Context, uri: Uri, maxBytes: Int): Picked? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        // Too big: say so without reading it all.
        if (size > maxBytes) return@withContext Picked(name, null, size)
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readUpTo(maxBytes + 1)
                if (bytes.size > maxBytes) Picked(name, null, bytes.size.toLong()) else Picked(name, bytes, bytes.size.toLong())
            }
        }.getOrNull()
    }

    suspend fun write(context: Context, uri: Uri, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
    }

    /** Puts an image into the gallery (Pictures/RadioMe). No permission needed on Android 10+. */
    suspend fun saveToGallery(context: Context, name: String, mime: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RadioMe")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                // Android 8-9: the app's own pictures folder, which needs no storage permission.
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return@runCatching false
                File(dir, name).writeBytes(bytes)
                true
            }
        }.getOrDefault(false)
    }

    fun shareText(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
