package ch.lab77.radar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Estimate
import ch.lab77.radar.data.Estimator
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus

/**
 * Moniteur de l'objet sélectionné : petit encadré en haut à droite, par-dessus la carte ou le radar,
 * toujours visible en marchant (retour terrain n°3, #10). Guide de marche compact, fiche complète sur ⓘ.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Monitor(d: Device, e: Estimate?, st: ScanStatus, onClose: () -> Unit) {
    var full by rememberSaveable { mutableStateOf(false) }
    // Tant que le moniteur est ouvert, le service mesure cet objet en rafale (#12)
    DisposableEffect(d.id) {
        ScanRepository.setGuideTarget(d.id)
        onDispose { ScanRepository.setGuideTarget(null) }
    }
    Column(
        Modifier.widthIn(max = 300.dp).heightIn(max = 420.dp).background(Palette.surface2.copy(alpha = 0.97f)).border(1.dp, Palette.green).padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.name.ifBlank { if (d.kind == Kind.WIFI) "<SSID caché>" else d.vendor.ifBlank { d.id } },
                color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1
            )
            SmallText(onClick = { full = !full }) { Text(if (full) "▲" else "ⓘ") }
            SmallText(onClick = onClose) { Text("✕") }
        }
        // Distance depuis moi (position calculée) et distance d'après le signal (ordre de grandeur) — retour n°10
        val fromMe = if (e?.lat != null && e.lon != null && st.lat != null && st.lon != null) Estimator.distanceM(st.lat, st.lon, e.lat, e.lon).toInt() else null
        val bySignal = Estimator.floorRadius(d.rssi, d.kind).toInt()
        Text(
            "à ~${fromMe?.let { "$it m" } ?: "? m"} de moi (position calculée) · signal ≈ ${bySignal} m",
            color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp
        )
        Text(
            "${d.rssi} dBm · ${d.kind.name} · ${d.category.label}" +
                (e?.takeIf { it.lat != null }?.let { " · ±${it.radius.toInt()} m · ${it.n} obs · ${it.persistence.label}" + (if (it.rttFix) " · RTT" else "") + (if (it.locked) " · 🔒 stable" else "") + (if (it.bearingFix) " · △ triangulé" else "") } ?: " · pas encore positionné"),
            color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
        val known by ScanRepository.whitelist.collectAsStateWithLifecycle()
        var aiming by rememberSaveable { mutableStateOf(false) }
        var ar by rememberSaveable { mutableStateOf(false) }
        val arOk = arState(LocalContext.current).let { it == ArState.READY || it == ArState.INSTALL }
        Text(
            "◎ Viser = mesurer la distance (viser son pied) · " + (if (arOk) "📷 Pointer = le poser exactement (caméra) · " else "") + "Connu = ne plus alerter",
            color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallText(onClick = { aiming = true }, enabled = st.pitch != null) { Text("◎ Viser") }
            if (arOk) SmallText(onClick = { ar = true }) { Text("📷 Pointer") }
            SmallText(onClick = { ScanRepository.setKnown(d.id, d.id !in known) }) { Text(if (d.id in known) "✓ Connu" else "Connu ?") }
        }
        if (aiming) RangeFinderScreen(d, st) { aiming = false }
        if (ar) ArScreen(d) { ar = false }
        PaceIndicator(st, d.kind)
        WalkGuide(d, st, compact = !full)
        if (full) {
            if (e != null && e.lat != null) Text(
                (if (e.rttFix) "Position TRILATÉRÉE (RTT) " else "Position estimée ") + "${"%.5f".format(e.lat)}, ${"%.5f".format(e.lon)}" +
                    (e.altM?.let { " · Δalt ${"%+.0f".format(it)} m (≈ ${"%+.0f".format(it / 3)} étage)" } ?: ""),
                color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 11.sp
            )
            DeviceDetail(d)
        }
    }
}
