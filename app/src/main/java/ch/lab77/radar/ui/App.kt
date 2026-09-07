package ch.lab77.radar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus
import ch.lab77.radar.data.ConfidenceRules

@Composable
fun App(onWifi: (Boolean) -> Unit, onBle: (Boolean) -> Unit, onCell: (Boolean) -> Unit, onQuit: () -> Unit, onStopAll: () -> Unit = {}) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val status by ScanRepository.status.collectAsStateWithLifecycle()
    val devices by ScanRepository.devices.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Palette.bg,
        topBar = { StatusBar(status, devices.size, onWifi, onBle, onCell) },
        bottomBar = {
            NavigationBar(containerColor = Palette.surface) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.List, null) }, label = { Text("Liste") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Radar, null) }, label = { Text("Radar") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Map, null) }, label = { Text("Carte") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Share, null) }, label = { Text("Session") })
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> ListScreen(devices)
                1 -> RadarScreen(devices, status)
                2 -> MapScreen(devices, status)
                else -> SessionScreen(devices, status, onQuit, onStopAll)
            }
        }
    }
}

@Composable
private fun StatusBar(st: ScanStatus, count: Int, onWifi: (Boolean) -> Unit, onBle: (Boolean) -> Unit, onCell: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("RADAR", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            Text("$count", color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            val sats = if (st.satsVisible > 0) " ${st.satsUsed}/${st.satsVisible}sat" else ""
            Text(
                (when { st.deadReckoning -> "ESTIME ±${st.accuracy?.toInt() ?: 0}m"; st.gpsHeld -> "GPS ±${st.accuracy?.toInt() ?: 0}m ⏸"; st.gpsFix -> "GPS ±${st.accuracy?.toInt() ?: 0}m"; else -> "GPS —" }) + sats,
                color = when { st.deadReckoning || st.gpsHeld -> Palette.amber; st.gpsFix -> Palette.green; else -> Palette.muted }, fontSize = 12.sp, fontFamily = FontFamily.Monospace
            )
            if (st.heading != null) Text("↑${st.heading.toInt()}°", color = Palette.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (st.baroAltM != null) Text("Δ${"%+.0f".format(st.baroAltM)}m", color = Palette.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (st.arTracking.startsWith("suivi")) Text("📷", fontSize = 12.sp)
            if (st.probe.startsWith("connectée")) Text("⚡sonde", color = Palette.violet, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (st.wifiOn && st.wifiThrottled)
                Text("throttle", color = Palette.amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactChip(st.wifiOn, { onWifi(!st.wifiOn) }, "Wi-Fi", selectedColor = Palette.green, leadingIcon = { Icon(Icons.Default.Wifi, null) })
            CompactChip(st.bleOn, { onBle(!st.bleOn) }, "BLE", selectedColor = Palette.blue, leadingIcon = { Icon(Icons.Default.Bluetooth, null) })
            CompactChip(st.cellOn, { onCell(!st.cellOn) }, "Cell", selectedColor = Palette.orange, leadingIcon = { Icon(Icons.Default.CellTower, null) })
            CompactChip(st.alertsOn, { ScanRepository.setAlerts(!st.alertsOn) }, "Bip")
        }
        ConfidenceLine(ConfidenceRules.myPosition(st), big = true)
    }
}
