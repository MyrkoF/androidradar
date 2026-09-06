package org.equalium.sonde.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.equalium.sonde.data.Category
import org.equalium.sonde.data.Device
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radar RSSI. Honnêteté d'affichage : le rayon est la seule mesure (signal → distance estimée),
 * l'angle est un hash stable de l'adresse — il n'y a pas de direction dans un RSSI.
 */
@Composable
fun RadarScreen(devices: Map<String, Device>) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val sweep by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "angle"
    )
    val active = devices.values.filter { it.ageMs < 120_000 }

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 - 8.dp.toPx()
            for (k in 1..4) drawCircle(Palette.grid, r * k / 4, c, style = Stroke(1.dp.toPx()))
            drawLine(Palette.grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
            drawLine(Palette.grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())

            val rad = Math.toRadians(sweep.toDouble())
            drawLine(Palette.green.copy(alpha = 0.6f), c, Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()), 2.dp.toPx())

            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(200, 217, 228, 232)
                textSize = 10.sp.toPx()
                typeface = android.graphics.Typeface.MONOSPACE
            }
            for (d in active) {
                val dist = ((-d.rssi - 30).coerceIn(0, 70) / 70f) * r
                val ang = Math.toRadians(angleFor(d.id).toDouble())
                val p = Offset(c.x + dist * cos(ang).toFloat(), c.y + dist * sin(ang).toFloat())
                val col = categoryColor(d.category)
                val alpha = if (d.ageMs < 30_000) 1f else 0.45f
                val dotR = if (d.category.isPriority) 7.dp.toPx() else 4.dp.toPx()
                drawCircle(col.copy(alpha = alpha), dotR, p)
                if (d.category.isPriority) drawCircle(col.copy(alpha = alpha * 0.5f), dotR * 2, p, style = Stroke(1.dp.toPx()))
                if (d.category.isPriority || d.rssi > -60) {
                    val label = d.name.ifBlank { d.vendor.ifBlank { d.id.takeLast(8) } }.take(14)
                    drawContext.canvas.nativeCanvas.drawText(label, p.x + dotR + 4f, p.y + 4f, paint)
                }
            }
            drawCircle(Palette.green, 3.dp.toPx(), c)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Legend("-30", Palette.text); Legend("-47", Palette.text); Legend("-65", Palette.text); Legend("-82", Palette.text); Legend("-100 dBm", Palette.text)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Legend("● cellulaire/flotte", Palette.red); Legend("● infra/caméra", Palette.amber)
            Legend("● industriel", Palette.violet); Legend("● public", Palette.green); Legend("● inconnu", Palette.blue)
        }
        Text(
            "${active.size} actifs · rayon = signal, angle = arbitraire (stable par adresse)",
            color = Palette.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun Legend(s: String, c: Color) = Text(s, color = c, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

private fun angleFor(id: String): Float {
    var h = 7
    for (ch in id) h = h * 31 + ch.code
    return ((h and 0x7fffffff) % 360).toFloat()
}
