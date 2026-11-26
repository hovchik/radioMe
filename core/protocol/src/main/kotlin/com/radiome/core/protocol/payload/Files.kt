package com.radiome.core.protocol.payload

/**
 * Small files sent as [ContentType.BINARY] payloads. The file name travels in the session header (or inside the
 * envelope when encrypted), which has little room, so long names are shortened while keeping the extension.
 */
object Files {
    /** Above this the composer warns about air time; the protocol itself takes up to 64 KB. */
    const val WARN_BYTES = 8 * 1024
    const val MAX_BYTES = 64 * 1024

    /** [name] cut to at most [maxBytes] UTF-8 bytes, keeping a short extension such as ".pdf" or ".gpx". */
    fun fitName(name: String, maxBytes: Int): String {
        val clean = name.substringAfterLast('/').filter { it >= ' ' && it !in "\\:*?\"<>|" }.trim().ifEmpty { "file" }
        if (utf8(clean) <= maxBytes) return clean
        val dot = clean.lastIndexOf('.')
        val ext = if (dot > 0 && clean.length - dot in 2..6) clean.substring(dot) else ""
        val budget = maxBytes - utf8(ext) - 1
        if (budget < 1) return cut(clean, maxBytes)
        return cut(clean.substring(0, clean.length - ext.length), budget) + "~" + ext
    }

    /** MIME type for saving and sharing a received file, from its extension. */
    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "gpx" -> "application/gpx+xml"
        "kml" -> "application/vnd.google-earth.kml+xml"
        "vcf" -> "text/x-vcard"
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    fun payload(name: String, bytes: ByteArray, maxNameBytes: Int): Payload {
        require(bytes.size in 1..MAX_BYTES) { "file must be 1 B to ${MAX_BYTES / 1024} KB" }
        return Payload(ContentType.BINARY, bytes, fitName(name, maxNameBytes))
    }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    private fun cut(s: String, maxBytes: Int): String {
        var end = s.length
        while (end > 0 && utf8(s.substring(0, end)) > maxBytes) end--
        // Do not leave half a surrogate pair.
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }
}
