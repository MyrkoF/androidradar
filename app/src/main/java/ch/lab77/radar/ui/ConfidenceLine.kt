package ch.lab77.radar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lab77.radar.data.Confidence
import ch.lab77.radar.data.Level

fun levelColor(l: Level): Color = when (l) { Level.SURE -> Palette.green; Level.APPROX -> Palette.amber; Level.NONE -> Palette.red }

/** Une ligne de confiance en clair : pastille de couleur, phrase, et le geste qui améliore. */
@Composable
fun ConfidenceLine(c: Confidence, big: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.padding(end = 6.dp).size(if (big) 12.dp else 9.dp).background(levelColor(c.level), CircleShape))
            Text(c.text, color = levelColor(c.level), fontFamily = FontFamily.Monospace, fontSize = if (big) 13.sp else 11.sp)
        }
        if (c.hint.isNotBlank()) Text("→ " + c.hint, color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}
