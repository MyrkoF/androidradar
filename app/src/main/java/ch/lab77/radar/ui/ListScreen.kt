package ch.lab77.radar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Kind

private enum class Filter(val label: String) { ALL("Tous"), WIFI("Wi-Fi"), BLE("BLE"), PRIORITY("Prioritaires"), ACTIVE("Actifs") }

@Composable
fun ListScreen(devices: Map<String, Device>) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    val list = devices.values.asSequence().filter {
        when (filter) {
            Filter.ALL -> true
            Filter.WIFI -> it.kind == Kind.WIFI
            Filter.BLE -> it.kind == Kind.BLE
            Filter.PRIORITY -> it.category.isPriority
            Filter.ACTIVE -> it.ageMs < 60_000
        }
    }.sortedWith(compareByDescending<Device> { it.category.isPriority }.thenByDescending { it.rssi }).toList()

    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Filter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
            }
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
            Text("${d.seenCount}×", color = Palette.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        if (open) {
            Column(Modifier.padding(start = 20.dp, top = 6.dp)) {
                Mono("MAC   ${d.id}")
                Mono("Fab.  ${d.vendorLong.ifBlank { "inconnu" }}")
                Mono("Cat.  ${d.category.label}")
                Mono("RSSI  ${d.rssi} dBm (meilleur ${d.bestRssi})")
                if (d.frequency > 0) Mono("Freq  ${d.frequency} MHz")
                if (d.capabilities.isNotBlank()) Mono("Caps  ${d.capabilities}")
                if (d.lat != null) Mono("Pos   ${"%.5f".format(d.lat)}, ${"%.5f".format(d.lon)} ±${d.accuracy?.toInt() ?: 0}m")
                Mono("Vu    ${d.seenCount}× · il y a ${d.ageMs / 1000}s")
            }
        }
    }
}

@Composable
private fun Mono(s: String) = Text(s, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

fun rssiColor(rssi: Int) = when {
    rssi >= -55 -> Palette.red
    rssi >= -70 -> Palette.amber
    rssi >= -85 -> Palette.green
    else -> Palette.muted
}
