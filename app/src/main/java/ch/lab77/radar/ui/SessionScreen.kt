package ch.lab77.radar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus
import ch.lab77.radar.export.Exporter

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
        BatteryBanner(st)
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

/**
 * Optimisation batterie : si elle est active, Android (et surtout OriginOS / HyperOS) tuera le relevé
 * écran éteint. On le détecte à chaque retour sur l'écran et on guide vers les réglages.
 */
@Composable
private fun BatteryBanner(st: ScanStatus) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    var guide by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxWidth().background(Palette.surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (ignoring) "Batterie : sans restriction ✓" else "!! Optimisation batterie ACTIVE — le relevé sera tué écran éteint",
            color = if (ignoring) Palette.green else Palette.amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
        if (st.wifiOn && st.wifiThrottled) Text(
            "!! Wi-Fi bridé (4 scans / 2 min) — Options développeur → « Limitation du scan Wi-Fi » à désactiver",
            color = Palette.amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ignoring) OutlinedButton(onClick = { requestIgnoreBatteryOptimizations(ctx) }) { Text("Désactiver") }
            OutlinedButton(onClick = { guide = !guide }) { Text(if (guide) "Masquer le guide" else "Réglages téléphone") }
        }
        if (guide) Text(GUIDE, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

private fun requestIgnoreBatteryOptimizations(ctx: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    for (i in listOf(direct, list, appInfo)) {
        try { ctx.startActivity(i); return } catch (_: Exception) {}
    }
}

private val GUIDE = """
Commun (tous Android)
 · Infos de l'app → Batterie → Sans restriction
 · Options développeur → Limitation du scan Wi-Fi : désactivée
 · Ne pas fermer l'app depuis les récents pendant un relevé

vivo (OriginOS)
 · i Gestionnaire → Batterie → Consommation élevée en arrière-plan → autoriser Radar
 · i Gestionnaire → Apps → Radar → Démarrage automatique : activé
 · Récents → glisser Radar vers le bas → cadenas (verrouiller)

Xiaomi (HyperOS)
 · Sécurité → Autostart → Radar : activé
 · Réglages → Apps → Radar → Économiseur de batterie → Aucune restriction
 · Récents → appui long sur Radar → cadenas (verrouiller)

Vérification : Wi-Fi + BLE lancés, écran éteint 10 min ; le compteur doit monter
et le journal ne doit pas contenir de ligne « aucun résultat depuis ».
""".trimIndent()
