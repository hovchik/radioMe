package com.radiome.feature.transceiver.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF1B6B50),
    secondary = Color(0xFF4C6358),
    tertiary = Color(0xFF3E6374),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8BD6B4),
    secondary = Color(0xFFB3CCBF),
    tertiary = Color(0xFFA6CCE0),
)

/** Colours for chunk cells and status, shared by the dashboard and history. */
object RadioColors {
    val ok = Color(0xFF2E7D32)
    val repaired = Color(0xFFF9A825)
    val missing = Color(0xFFC62828)
    val pending = Color(0x33888888)
}

/**
 * @param dynamicColor follow the wallpaper colours on Android 12+. Screenshot tests turn it off to show the app's
 *   own green palette.
 */
@Composable
fun RadioMeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
