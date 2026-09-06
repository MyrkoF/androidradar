package ch.lab77.radar.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Kind
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Radar RSSI. Honnêteté d'affichage : le rayon est la seule mesure (signal → distance estimée, ordre de
 * grandeur), l'angle est un hash stable de l'adresse — il n'y a pas de direction dans un RSSI.
 * Couleur = type (Wi-Fi / BLE) ; anneau rouge = catégorie à surveiller. L'échelle radiale est non linéaire
 * (exposant 1,4) pour écarter les signaux faibles, qui sont les plus nombreux. Pincer ou boutons pour zoomer ;
 * tap sur un point → fiche détail. Le radar tient toujours dans l'écran.
 */
private const val CENTER_DBM = -30f
private const val SPAN_MIN = 20f
private const val SPAN_MAX = 70f
private const val CURVE = 1.4

@Composable
fun RadarScreen(devices: Map<String, Device>) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val sweep by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "angle"
    )
    var span by rememberSaveable { mutableFloatStateOf(65f) }   // dBm entre le centre et le bord (65 → bord = -95)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val outer = (CENTER_DBM - span).toInt()
    val active = devices.values.filter { it.ageMs < 120_000 && ViewFilter.accepts(it) }
    val visible = active.filter { it.rssi >= outer }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { span = (span + 10f).coerceAtMost(SPAN_MAX) }, enabled = span < SPAN_MAX) { Text("−") }
            OutlinedButton(onClick = { span = (span - 10f).coerceAtLeast(SPAN_MIN) }, enabled = span > SPAN_MIN) { Text("+") }
            Text("bord $outer dBm · ${visible.size}/${active.size}", color = Palette.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            FilterMenu()
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> span = (span / zoom).coerceIn(SPAN_MIN, SPAN_MAX) }
                    }
                    .pointerInput(visible, span) {
                        detectTapGestures { pos ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val r = minOf(size.width, size.height) / 2f - 10.dp.toPx()
                            val hit = visible.minByOrNull { (pointFor(it, c, r, span) - pos).getDistance() }
                            selected = if (hit != null && (pointFor(hit, c, r, span) - pos).getDistance() < 28.dp.toPx()) hit.id else null
                        }
                    }
            ) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension / 2 - 10.dp.toPx()
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(200, 217, 228, 232)
                    textSize = 10.sp.toPx()
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                val ringPaint = android.graphics.Paint(paint).apply { color = android.graphics.Color.argb(170, 124, 143, 151) }
                for (k in 1..4) {
                    val frac = k / 4f
                    val rk = r * frac
                    drawCircle(Palette.grid, rk, c, style = Stroke(1.dp.toPx()))
                    val dbm = (CENTER_DBM - span * frac.toDouble().pow(1 / CURVE).toFloat()).toInt()
                    drawContext.canvas.nativeCanvas.drawText("$dbm dBm ≈${approxDistance(dbm)}", c.x + 4f, c.y - rk - 3f, ringPaint)
                }
                drawLine(Palette.grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
                drawLine(Palette.grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())

                val rad = Math.toRadians(sweep.toDouble())
                drawLine(Palette.green.copy(alpha = 0.6f), c, Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()), 2.dp.toPx())

                for (d in visible) {
                    val p = pointFor(d, c, r, span)
                    val col = kindColor(d.kind)
                    val alpha = if (d.ageMs < 30_000) 1f else 0.45f
                    val dotR = if (d.category.isPriority) 6.dp.toPx() else 4.dp.toPx()
                    if (d.id == selected) drawCircle(Palette.text, dotR + 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                    drawCircle(col.copy(alpha = alpha), dotR, p)
                    if (d.category.isPriority) drawCircle(Palette.red.copy(alpha = alpha), dotR + 3.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                    if (d.category.isPriority || d.rssi > -60 || d.id == selected) {
                        val label = d.name.ifBlank { d.vendor.ifBlank { d.id.takeLast(8) } }.take(14)
                        drawContext.canvas.nativeCanvas.drawText(label, p.x + dotR + 4f, p.y + 4f, paint)
                    }
                }
                drawCircle(Palette.green, 3.dp.toPx(), c)
            }
        }
        Column(Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 10.dp, vertical = 6.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend("● Wi-Fi", Palette.green); Legend("● BLE", Palette.blue); Legend("◎ à surveiller", Palette.red); Legend("estompé = vu > 30 s", Palette.muted)
            }
            Text(
                "rayon = signal (≈ distance, ordre de grandeur) · angle arbitraire, stable par adresse · pincer = zoom · tap = détail",
                color = Palette.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            val sel = selected?.let { devices[it] }
            if (sel != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        sel.name.ifBlank { if (sel.kind == Kind.WIFI) "<SSID caché>" else "<sans nom>" },
                        color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { selected = null }) { Text("✕") }
                }
                DeviceDetail(sel)
            }
        }
    }
}

/** Rayon normalisé : (dB depuis le centre / span)^1,4 — plus de place pour les signaux faibles. */
private fun pointFor(d: Device, c: Offset, r: Float, span: Float): Offset {
    val x = ((-d.rssi - CENTER_DBM).coerceIn(0f, span) / span).toDouble().pow(CURVE).toFloat()
    val ang = Math.toRadians(angleFor(d.id).toDouble())
    return Offset(c.x + x * r * cos(ang).toFloat(), c.y + x * r * sin(ang).toFloat())
}

/** Distance d'après un modèle de perte en espace libre (Wi-Fi 2,4 GHz, exposant 2,7). Ordre de grandeur seulement. */
private fun approxDistance(dbm: Int): String {
    val m = 10.0.pow((-40.0 - dbm) / 27.0)
    return when {
        m < 1 -> "<1 m"
        m < 10 -> "${m.toInt()} m"
        m < 100 -> "${(m / 5).toInt() * 5} m"
        else -> "${(m / 50).toInt() * 50} m"
    }
}

@Composable
private fun Legend(s: String, c: Color) = Text(s, color = c, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

private fun angleFor(id: String): Float {
    var h = 7
    for (ch in id) h = h * 31 + ch.code
    return ((h and 0x7fffffff) % 360).toFloat()
}
