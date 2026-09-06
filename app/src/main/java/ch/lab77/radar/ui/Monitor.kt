package ch.lab77.radar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Estimate
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus

/**
 * Moniteur de l'objet sélectionné : petit encadré en haut à droite, par-dessus la carte ou le radar,
 * toujours visible en marchant (retour terrain n°3, #10). Guide de marche compact, fiche complète sur ⓘ.
 */
@Composable
fun Monitor(d: Device, e: Estimate?, st: ScanStatus, onClose: () -> Unit) {
    var full by rememberSaveable { mutableStateOf(false) }
    // Tant que le moniteur est ouvert, le service mesure cet objet en rafale (#12)
    DisposableEffect(d.id) {
        ScanRepository.setGuideTarget(d.id)
        onDispose { ScanRepository.setGuideTarget(null) }
    }
    Column(
        Modifier.widthIn(max = 300.dp).heightIn(max = 420.dp).background(Palette.surface.copy(alpha = 0.93f)).padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.name.ifBlank { if (d.kind == Kind.WIFI) "<SSID caché>" else d.vendor.ifBlank { d.id } },
                color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1
            )
            TextButton(onClick = { full = !full }) { Text(if (full) "▲" else "ⓘ") }
            TextButton(onClick = onClose) { Text("✕") }
        }
        Text(
            "${d.rssi} dBm · ${d.kind.name} · ${d.category.label}" +
                (e?.takeIf { it.lat != null }?.let { " · ±${it.radius.toInt()} m · ${it.n} obs · ${it.persistence.label}" + (if (it.rttFix) " · RTT" else "") + (if (it.locked) " · 🔒 stable" else "") + (if (it.bearingFix) " · △ triangulé" else "") } ?: " · pas encore positionné"),
            color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
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
