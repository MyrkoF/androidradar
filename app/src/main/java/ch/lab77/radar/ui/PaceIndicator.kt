package ch.lab77.radar.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanStatus
import kotlinx.coroutines.delay

/**
 * Voyant de rythme (#19) : vert / orange / rouge selon la vitesse de rotation, la cadence de marche et l'état
 * du suivi caméra, rapporté au rythme des mesures du capteur suivi. Rouge = bip court + vibration toutes
 * les 1,5 s (si « Bip » est actif). Comme un scanner 3D qui dit « trop vite ».
 */
enum class Pace { GOOD, WARN, BAD }

fun pace(st: ScanStatus, kind: Kind?, ar: Boolean): Pair<Pace, String> {
    if (ar && st.arTracking.startsWith("perdu")) return Pace.BAD to "Décor perdu — revenez lentement vers un endroit déjà vu"
    if (ar && st.arTracking.startsWith("initialisation")) return Pace.WARN to "Initialisation — bougez lentement le téléphone"
    val turnLimit = when (kind) { Kind.WIFI -> 10f; Kind.CELL -> 6f; Kind.BLE -> 30f; else -> 15f }   // °/s pour un secteur de 30° par mesure
    val turn = st.turnRateDps
    if (turn > turnLimit * 2) return Pace.BAD to "Tournez MOINS vite (${turn.toInt()}°/s, max ~${turnLimit.toInt()})"
    if (st.stepRate > 2f) return Pace.BAD to "Marchez moins vite (${"%.1f".format(st.stepRate)} pas/s)"
    if (turn > turnLimit) return Pace.WARN to "Un peu vite (${turn.toInt()}°/s) — ralentissez"
    if (st.stepRate > 1.6f) return Pace.WARN to "Cadence élevée — ralentissez un peu"
    return Pace.GOOD to (if (turn < 2f && st.stepRate == 0f) "Immobile — bon pour mesurer" else "Rythme correct")
}

@Composable
fun PaceIndicator(st: ScanStatus, kind: Kind?, ar: Boolean = false) {
    val ctx = LocalContext.current
    val (p, text) = pace(st, kind, ar)
    val color = when (p) { Pace.GOOD -> Palette.green; Pace.WARN -> Palette.amber; Pace.BAD -> Palette.red }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() }
    LaunchedEffect(p, st.alertsOn) {
        while (p == Pace.BAD && st.alertsOn) {
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
            vibrate(ctx)
            delay(1500)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

private fun vibrate(ctx: Context) {
    try {
        val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {}
}
