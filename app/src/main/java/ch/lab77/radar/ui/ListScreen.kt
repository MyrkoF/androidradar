package ch.lab77.radar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lab77.radar.data.Category
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Kind

@Composable
fun ListScreen(devices: Map<String, Device>) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var help by rememberSaveable { mutableStateOf(false) }

    val list = devices.values.asSequence().filter(ViewFilter::accepts)
        .sortedWith(compareByDescending<Device> { it.category.isPriority }.thenByDescending { it.rssi }).toList()

    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${list.size} affichés / ${devices.size} · tri : à surveiller d'abord, puis signal",
                color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { help = !help }) { Icon(Icons.Default.Info, "Légende", tint = if (help) Palette.green else Palette.muted) }
            FilterMenu()
        }
        if (help) Column(Modifier.fillMaxWidth().background(Palette.surface).padding(10.dp)) {
            Text("Légende", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendDot(Palette.red, "cellulaire / flotte"); LegendDot(Palette.amber, "infra / caméra"); LegendDot(Palette.violet, "industriel")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendDot(Palette.blue, "routeur Wi-Fi / box"); LegendDot(Palette.green, "grand public"); LegendDot(Palette.text, "inconnu"); LegendDot(Palette.muted, "MAC aléatoire")
            }
            Mono("Pastille = catégorie déduite du FABRICANT (adresse MAC) — une déduction, pas une certitude")
            Mono("Rouge / ambre / violet = à surveiller (bip à l'apparition)")
            Mono("Chiffre = signal dBm : rouge ≥ -55 (très proche), ambre ≥ -70, vert ≥ -85, gris au-delà")
            Mono("« vu 25× » = nombre d'observations dans la session · ligne grisée = plus vu depuis 1 min")
            Mono("⚙ en haut à droite = filtres (types, catégories, disparus), communs à Liste, Radar et Carte")
            Mono("Toucher une ligne = détail")
        }
        LazyColumn {
            items(list, key = { it.id }) { d ->
                DeviceRow(d, expanded == d.id) { expanded = if (expanded == d.id) null else d.id }
                HorizontalDivider(color = Palette.surface2)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: Device, open: Boolean, onClick: () -> Unit) {
    val stale = d.ageMs > 60_000
    val fg = if (stale) Palette.muted else Palette.text
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(categoryColor(d.category), CircleShape))
            Text(
                "${d.rssi}", color = rssiColor(d.rssi), fontFamily = FontFamily.Monospace,
                fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 2.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(d.name.ifBlank { if (d.kind == Kind.WIFI) "<SSID caché>" else "<sans nom>" }, color = fg, fontSize = 15.sp, maxLines = 1)
                Text(
                    "${d.kind.name} · ${d.vendor.ifBlank { "?" }} · ${d.band}" + (if (d.kind == Kind.WIFI) " ch${d.channel} · ${d.security}" else ""),
                    color = Palette.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
            Text("vu ${d.seenCount}×", color = Palette.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        if (open) Column(Modifier.padding(start = 20.dp, top = 6.dp)) { DeviceDetail(d) }
    }
}

/** Fiche détail d'un appareil — partagée entre la Liste, le Radar et la Carte. */
@Composable
fun DeviceDetail(d: Device) {
    Column {
        Mono("MAC   ${d.id}")
        Mono("Type  ${d.kind.name} · ${d.band}" + (if (d.kind == Kind.WIFI) " · ch${d.channel} · ${d.security}" else ""))
        Mono("Fab.  ${d.vendorLong.ifBlank { "inconnu" }}")
        Mono("Cat.  ${d.category.label}" + (if (d.category.isPriority) " (à surveiller)" else "") + " — déduit du fabricant")
        Mono("RSSI  ${d.rssi} dBm (meilleur ${d.bestRssi})")
        if (d.frequency > 0) Mono("Freq  ${d.frequency} MHz")
        if (d.capabilities.isNotBlank()) Mono("Caps  ${d.capabilities}")
        if (d.lat != null) Mono("Pos   ${"%.5f".format(d.lat)}, ${"%.5f".format(d.lon)} ±${d.accuracy?.toInt() ?: 0}m")
        Mono("Vu    ${d.seenCount}× · il y a ${d.ageMs / 1000}s")
    }
}

@Composable
private fun Mono(s: String) = Text(s, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
private fun LegendDot(c: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Text(label, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

fun rssiColor(rssi: Int) = when {
    rssi >= -55 -> Palette.red
    rssi >= -70 -> Palette.amber
    rssi >= -85 -> Palette.green
    else -> Palette.muted
}
