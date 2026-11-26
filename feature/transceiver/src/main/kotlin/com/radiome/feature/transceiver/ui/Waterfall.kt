package com.radiome.feature.transceiver.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

private const val ROWS = 80
private const val FLOOR_DB = -100f
private const val TOP_DB = -20f

/**
 * Live input spectrum, newest row on top, 300-2700 Hz left to right. Tones of a burst show as bright steps;
 * a hum or a stuck carrier shows as a steady vertical line.
 */
@Composable
fun WaterfallCard(rows: Flow<FloatArray>, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Only while the app is on screen: stopping the collection turns the link's FFT off.
    LaunchedEffect(rows, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        rows.collect { row ->
            val b = bitmap.takeIf { it?.width == row.size } ?: Bitmap.createBitmap(row.size, ROWS, Bitmap.Config.ARGB_8888).also { bitmap = it }
            val pixels = IntArray(row.size * ROWS)
            b.getPixels(pixels, 0, row.size, 0, 0, row.size, ROWS)
            // Scroll down one row and draw the new one on top.
            System.arraycopy(pixels, 0, pixels, row.size, row.size * (ROWS - 1))
            for (i in row.indices) pixels[i] = heat((row[i] - FLOOR_DB) / (TOP_DB - FLOOR_DB))
            b.setPixels(pixels, 0, row.size, 0, 0, row.size, ROWS)
            tick++
        }
        }
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Waterfall", style = MaterialTheme.typography.titleSmall)
            val image = bitmap?.asImageBitmap()
            Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Live spectrum of the received audio" }) {
                tick // redraw on every new row
                if (image != null) {
                    drawImage(
                        image,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        dstOffset = IntOffset.Zero,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("300", "900", "1500", "2100", "2700 Hz").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** Dark blue through cyan and yellow to white. */
private fun heat(v: Float): Int {
    val x = v.coerceIn(0f, 1f)
    val r: Float
    val g: Float
    val b: Float
    when {
        x < 0.33f -> { val t = x / 0.33f; r = 0f; g = 0.4f * t; b = 0.25f + 0.6f * t }
        x < 0.66f -> { val t = (x - 0.33f) / 0.33f; r = t; g = 0.4f + 0.5f * t; b = 0.85f * (1 - t) }
        else -> { val t = (x - 0.66f) / 0.34f; r = 1f; g = 0.9f + 0.1f * t; b = t }
    }
    return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
}
