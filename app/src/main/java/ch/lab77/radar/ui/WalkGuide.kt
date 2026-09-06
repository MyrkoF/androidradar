package ch.lab77.radar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Guide de marche (cahier §4 « mode marche », §4 ter « masquage corporel ») pour l'appareil sélectionné :
 * tendance approche / éloigne, et rose des caps où le signal est le plus fort quand on tourne sur soi-même
 * téléphone devant — le corps atténue ce qui est derrière. Indication (±30°), jamais mesure.
 */
@Composable
fun WalkGuide(d: Device, st: ScanStatus, compact: Boolean = false) {
    var samples by remember { mutableStateOf(ScanRepository.samplesOf(d.id)) }
    LaunchedEffect(d.id) {
        while (true) { samples = ScanRepository.samplesOf(d.id); delay(500) }
    }
    val now = System.currentTimeMillis()
    val recent = samples.filter { now - it.t <= 5_000 }.map { it.rssi }
    val before = samples.filter { now - it.t in 5_001..15_000 }.map { it.rssi }
    val trend = if (recent.isNotEmpty() && before.isNotEmpty()) recent.average() - before.average() else null
    val trendText = when {
        trend == null -> "tendance : pas assez de mesures"
        trend >= 3 -> "▲ approche (+${"%.0f".format(trend)} dB)"
        trend <= -3 -> "▼ éloigne (${"%.0f".format(trend)} dB)"
        else -> "— stable"
    }
    val trendColor = when { trend == null -> Palette.muted; trend >= 3 -> Palette.green; trend <= -3 -> Palette.amber; else -> Palette.text }

    // Rose des caps : moyenne du signal par secteur de 30° sur les 40 dernières secondes
    val bins = DoubleArray(12); val counts = IntArray(12)
    for (s in samples) {
        val h = s.heading ?: continue
        if (now - s.t > 40_000) continue
        val b = ((h + 15f) % 360f / 30f).toInt().coerceIn(0, 11)
        bins[b] += s.rssi; counts[b]++
    }
    val filled = counts.count { it > 0 }
    val means = DoubleArray(12) { if (counts[it] > 0) bins[it] / counts[it] else Double.NaN }
    val best = means.withIndex().filter { !it.value.isNaN() }.maxByOrNull { it.value }?.index
    val minMean = means.filter { !it.isNaN() }.minOrNull() ?: -100.0
    val maxMean = means.filter { !it.isNaN() }.maxOrNull() ?: -30.0

    Column(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!compact) Text("Guide de marche", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(trendText, color = trendColor, fontFamily = FontFamily.Monospace, fontSize = if (compact) 13.sp else 12.sp)
        if (d.rttM != null) Text("distance MESURÉE (Wi-Fi RTT) : ${"%.1f".format(d.rttM)} m ±${"%.1f".format(d.rttStdM ?: 0f)}", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        else if (d.rttCapable) Text("AP compatible RTT : distance mesurée dès que possible", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        if (st.heading == null) {
            Text("Pas de boussole : tendance seulement.", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(if (compact) 90.dp else 120.dp)) {
                val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2 - 4.dp.toPx()
                drawCircle(Palette.grid, r, c, style = Stroke(1.dp.toPx()))
                for (i in 0 until 12) {
                    val m = means[i]; if (m.isNaN()) continue
                    val len = (0.25 + 0.75 * (m - minMean) / (maxMean - minMean + 1e-3)).toFloat() * r
                    val a = Math.toRadians(i * 30.0 - 90.0)
                    val col = if (i == best) Palette.green else Palette.blue.copy(alpha = 0.6f)
                    drawLine(col, c, Offset(c.x + len * cos(a).toFloat(), c.y + len * sin(a).toFloat()), 6.dp.toPx())
                }
                // aiguille = mon cap actuel (nord en haut)
                val h = Math.toRadians(st.heading.toDouble() - 90.0)
                drawLine(Palette.text, c, Offset(c.x + r * cos(h).toFloat(), c.y + r * sin(h).toFloat()), 2.dp.toPx())
                drawCircle(Palette.text, 3.dp.toPx(), c)
            }
            Column {
                if (filled < 4) Text(if (compact) "Tournez sur vous-même\n(${filled}/12)" else "Tournez lentement sur vous-même,\ntéléphone devant vous (${filled}/12 secteurs).", color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                else Text(if (compact) "→ ${best?.let { it * 30 } ?: "?"}° (±30°)" else "Signal le plus fort vers ${best?.let { it * 30 } ?: "?"}° (±30°).\nAvancez dans cette direction, puis refaites un tour.", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = if (compact) 13.sp else 11.sp)
                if (!compact) Text("Nord en haut · barre verte = secteur le plus fort · aiguille = mon cap", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}
