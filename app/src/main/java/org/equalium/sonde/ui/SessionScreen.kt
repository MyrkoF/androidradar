package org.equalium.sonde.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.equalium.sonde.data.Device
import org.equalium.sonde.data.Kind
import org.equalium.sonde.data.ScanRepository
import org.equalium.sonde.data.ScanStatus
import org.equalium.sonde.export.Exporter

@Composable
fun SessionScreen(devices: Map<String, Device>, st: ScanStatus) {
    val ctx = LocalContext.current
    val all = devices.values
    var log by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        ScanRepository.log.collect { line -> log = (listOf(line) + log).take(200) }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val durMin = (System.currentTimeMillis() - st.sessionStart) / 60_000
        Text(
            "Session ${durMin} min · Wi-Fi ${all.count { it.kind == Kind.WIFI }} · BLE ${all.count { it.kind == Kind.BLE }} · prioritaires ${all.count { it.category.isPriority }}" +
                (if (st.lat != null) "\n@ ${"%.5f".format(st.lat)}, ${"%.5f".format(st.lon)}" else "\nGPS : pas de fix"),
            color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Exporter.share(ctx, Exporter.fileName("csv"), "text/csv", Exporter.wigleCsv(all)) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.green, contentColor = Palette.bg)) { Text("CSV WiGLE") }
            Button(onClick = { Exporter.share(ctx, Exporter.fileName("json"), "application/json", Exporter.json(all, st)) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.blue, contentColor = Palette.bg)) { Text("JSON") }
            Button(onClick = { Exporter.share(ctx, Exporter.fileName("md"), "text/plain", Exporter.debrief(all, st)) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.amber, contentColor = Palette.bg)) { Text("Débrief") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ScanRepository.clearSession() }) { Text("Nouvelle session") }
            val counts = ScanRepository.db()?.let { runCatching { it.counts() }.getOrNull() }
            if (counts != null) Text(
                "Base : ${counts.first} appareils, ${counts.second} positions",
                color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp)
            )
        }
        Text("Journal", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        LazyColumn(Modifier.fillMaxSize().background(Palette.surface).padding(6.dp)) {
            items(log) { line ->
                Text(
                    line, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = if (line.startsWith("!!")) Palette.amber else Palette.text
                )
            }
        }
    }
}
