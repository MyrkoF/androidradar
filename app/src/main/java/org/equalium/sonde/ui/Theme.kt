package org.equalium.sonde.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.equalium.sonde.data.Category
import org.equalium.sonde.data.Kind

object Palette {
    val bg = Color(0xFF0B1215)
    val surface = Color(0xFF121C21)
    val surface2 = Color(0xFF1A272E)
    val text = Color(0xFFD9E4E8)
    val muted = Color(0xFF7C8F97)
    val green = Color(0xFF3DDC97)
    val amber = Color(0xFFFFB74D)
    val red = Color(0xFFFF5C5C)
    val blue = Color(0xFF5CB8FF)
    val violet = Color(0xFFB39DFF)
    val grid = Color(0xFF1F3A3A)
}

fun categoryColor(c: Category): Color = when (c) {
    Category.CELLULAR_ROUTER -> Palette.red
    Category.FLEET -> Palette.red
    Category.CAMERA -> Palette.amber
    Category.NETWORK_INFRA -> Palette.amber
    Category.INDUSTRIAL -> Palette.violet
    Category.CONSUMER -> Palette.green
    Category.RANDOMIZED -> Palette.muted
    Category.UNKNOWN -> Palette.blue
}

fun kindColor(k: Kind): Color = if (k == Kind.WIFI) Palette.green else Palette.blue

private val scheme = darkColorScheme(
    primary = Palette.green,
    onPrimary = Palette.bg,
    secondary = Palette.blue,
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.surface2,
    onSurfaceVariant = Palette.muted,
    error = Palette.red,
)

@Composable
fun SondeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
